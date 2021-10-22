(ns assembly-explainer.compiler.machine
  [:require [reagent.core :as r]
   [assembly-explainer.compiler.parser :refer [parse]]
   [assembly-explainer.compiler.core :as c]
   [assembly-explainer.state]
   [goog.string :as gstr]
   [goog.string.format]])

;; Add the given value to the last element in coll
(defn add-to-last [coll x]
  (update coll (dec (count coll)) #(+ % x)))

;; Get the value of the register with the given name
(defn get-register-value [state name]
  (get-in state [:registers (keyword name)]))

;; Adds the given offset to the given path
;; 0 -> 0 + offset
;; [:memory :stack 0] -> [:memory :stack 0+offset]
(defn add-offset [path offset]
  (if (number? path)
    (+ path offset)
    (add-to-last path offset)))

;; Compute the location an expression like '0x5(%rsp)' points to.
(defn get-indirection-location [state [_ & location]]
  (let [base-value (get-register-value state (first location))]
    (case (count location)
      1 base-value
      2 (add-offset base-value (second location)))))

;; Take values like 0 or [:register "rax"] and return an array which can be used for
;; get-in and update-in like [:stack :memory 0] or [:registers :rax] respectively
(defn complete-state-path [state arg]
  (cond
    (number? arg) [:memory :stack :bytes arg]
    (= (first arg) :register) [:registers (keyword (second arg))]
    (= (first arg) :indirection) (complete-state-path state (get-indirection-location state arg))))

;; Take and argument and resolve it to a literal value.
(defn resolve-src [state arg]
  (if (= (first arg) :literal)
    (second arg)
    (get-in state (complete-state-path state arg))))

(defn update-path [state path f]
  (update-in state (complete-state-path state path) f))

(defn add-register [state register n]
  (update-path state register #(+ % n)))
(def inc-register #(add-register %1 %2 1))
(def dec-register #(add-register %1 %2 -1))

;; main processing function
(defmulti process-instruction (fn [_ [op]] (keyword op)))

(defmethod process-instruction :default [_ [op]]
  (js/console.log "Can't recognize " (str op) "."))

(defn find-first [coll f] (first (filter f coll)))

;; Check if a value is in the given range
(defn in-range [range x] (& (>= x (first range)) (<= x (second range))))

;; Check if index is in the range of the given stack object
(defn in-stack-object-range [o index] (in-range (:range o) index))

;; Applies f to i if p(i) is true
(defn update-if [i p f] (if (p i) (f i) (i)))

;; Get the path of the stack object associated with the given stack index.
(defn get-stack-object-path [state index] ())

;; Get the stack objects asociated with the given index
(defn get-stack-objects [state index] (filter #(in-stack-object-range % index) (get-in state [:memory :stack :indeces])))

;; Get the first stack object associated with the given index
(defn get-stack-object [state index] (first (get-stack-objects state index)))

;; Apply f to the stack object that contains the given index
(defn update-stack-object [state index f] (update-in state [:memory :stack :indices]
                                                     (fn [indices] (map (fn [object] (update-if object #(in-stack-object-range % index) f))) indices)))

;; Return true if there is a stack object associated with the given index
(defn has-stack-object [state index] (not-empty (count (get-stack-objects state index))))

(defn mov2 [state [src dest]]
  (if (& (= (first dest) :indirection) (= (second dest) :rsp))
    (-> state
        (assoc-in (complete-state-path state dest) (resolve-src state src))
        (assoc-in [:memory :stack :indices] ))))

(defn mov [state [src dest]]
  (assoc-in state (complete-state-path state dest) (resolve-src state src)))

(defmethod process-instruction :mov [state [_ src dest]]
  (mov state [src dest]))
  ;;  (assoc-in state (complete-state-path state dest) (resolve-src state src)))

(defn push [size [state [_ src]]]
  (-> state
      ;; write the bytes
      (mov [src [:indirection :rsp]])
      ;; add something to the indeces
      ()
      (add-register [:register :rsp] (* size -1))))

(defmethod process-instruction :push [& args] (push 8 args))
(defmethod process-instruction :pushq [& args] (push 8 args))
(defmethod process-instruction :pushl [& args] (push 4 args))
(defmethod process-instruction :pushw [& args] (push 2 args))

#_(defn pop [] ())

(defmethod process-instruction :pop [state [_ dest]] (-> state
                                                         (inc-register [:register :rsp])
                                                         (mov [[:indirection :rsp] dest])))

(defmethod process-instruction :add [state [src dest]] (let [dest-path (complete-state-path state dest)
                                                             src-path  (complete-state-path state src)]
                                                         (update-in state dest-path + (get-in state src-path))))

(defn sub [state [src dest]] (let [dest-path (complete-state-path state dest)
                                   src-path  (complete-state-path state src)]
                               (update-in state dest-path - (get-in state src-path))))

(defmethod process-instruction :sub [state [label]] ())

(defn mul [state [src dest]] (let [dest-path (complete-state-path state dest)
                                   src-path  (complete-state-path state src)]
                               (update-in state dest-path * (get-in state src-path))))

(defmethod process-instruction :mul [state [label]] ())

(defn div [state [src]] (let [dest-path (complete-state-path state [:register :rax])
                              src-path  (complete-state-path state src)]
                          (update-in state dest-path / (get-in state src-path))))

(defmethod process-instruction :div [state [label]] ())

(defmethod process-instruction :jmp [state [label]] ())

;; main stepping function
(defn step [state] (let [rip (get-register-value state :rip)
                         ins (get-in state [:memory :program :instructions rip])]
                     (-> state
                         (process-instruction ins)
                         (inc-register [:register :rip]))))

;; initializing the program state
(def starting-register nil)

(def starting-registers (assoc (->> c/registers-raw
                                    (map (fn [r] [(keyword r) starting-register]))
                                    (into {}))
                               :rip 0))

(defn init-program-state [program-input]
  (r/atom {:registers starting-registers
           :flags []
           :memory {:program {:instructions (parse program-input)}
                    :stack {:bytes []
                            :indices []}}}))

;; REPL
(comment
  ;; stub!
  (def state (init-program-state (first assembly-explainer.state/programs)))

  (swap! state step)

  (get-indirection-location @state [:indirection :rsp])

  (complete-state-path state [:indirection :rsp])

  (def v [1 2 3])

  (mov @state [[:register :rsp] [:register :rbp]])
  (mov @state [[:literal 1] [:indirection :rsp 1]])

  @state)

