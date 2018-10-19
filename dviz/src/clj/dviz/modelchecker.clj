(ns dviz.modelchecker
  (:require
   [clojure.core.async :refer [go >!! <!! chan]]))

(defprotocol IState
  (restart! [this] "Reset the state to the beginning of time")
  (actions [this] "Possible next actions in this state")
  (run! [this action] "Run action")
  (matches? [this pred] "Does this state match a predicate?"))


(defrecord CoolNumberProblem [x y]
  IState
  (restart! [this] (->CoolNumberProblem 0 0))
  (actions [this] [:inc-x :inc-y])
  (run! [this action]
    (if (= action :inc-x)
      (->CoolNumberProblem (inc x) y)
      (->CoolNumberProblem x (inc y))))
  (matches? [this pred] (pred x y)))

(defn prefix? [a b]
  (if (<= (count a) (count b))
    (= a (take (count a) b))
    false))

(defn new-actions [state current]
  (map #(conj current %) (actions state)))

(defn dfs
  ([state pred max-depth] (dfs state pred max-depth 3))
  ([state pred max-depth delta-depth]
   (loop [state state
          depth delta-depth
          worklist (new-actions state [])
          next-worklist ()
          current []]
     (cond
       (and (empty? worklist)
            (or (empty? next-worklist)
                (> (+ depth delta-depth) max-depth)))
       {:result :not-found}
       
       (empty? worklist)
       (recur state (+ depth delta-depth) next-worklist () current)

       :else
       (let [next (first worklist)]
         (if (prefix? current next)
           (let [state (reduce run! state (drop (count current) next))]
             (if (matches? state pred)
               {:result :found :trace next :state state}
               (let [acs (new-actions state next)]
                 (if (< (count next) depth)
                   (recur state
                          depth
                          (concat acs (rest worklist))
                          next-worklist
                          next)
                   (recur state
                          depth
                          (rest worklist)
                          (concat next-worklist acs)
                          next)))))
           (recur (restart! state) depth worklist next-worklist [])))))))
