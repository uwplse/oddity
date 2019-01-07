(ns oddity.modelchecker
  (:require
   [clojure.core.async :refer [go >!! <!! chan]]
   [taoensso.tufte :refer [p]]))

(defprotocol IState
  (restart! [this] "Reset the state to the beginning of time")
  (actions [this pred] "Possible next actions in this state (sorted based on pred)")
  (run-action! [this action] "Run action")
  (matches? [this pred] "Does this state match a predicate?"))

(defn prefix? [a b]
  (if (<= (count a) (count b))
    (= a (take (count a) b))
    false))

(defn new-actions [pred state current]
  (vec (map #(conj current %) (actions state pred))))

(defn dfs
  ([state pred max-depth] (dfs state pred max-depth 3))
  ([state pred max-depth delta-depth]
   (loop [state (restart! state)
          depth delta-depth
          worklist (new-actions pred state [])
          next-worklist ()
          current []
          n-explored 0]
     (when (= (mod n-explored 100) 0)
       (prn n-explored))
     (cond
       (and (empty? worklist)
            (or (empty? next-worklist)
                (> (+ depth delta-depth) max-depth)))
       {:result :not-found}
       
       (empty? worklist)
       (do
         (prn "Incrementing depth")
         (recur state (+ depth delta-depth) next-worklist () current (inc n-explored)))

       :else
       (let [next (first worklist)]
         (if (prefix? current next)
           (let [state (reduce run-action! state (drop (count current) next))]
             (if (matches? state pred)
               {:result :found :trace next :state state}
               (let [acs (new-actions pred state next)]
                 (if (< (count next) depth)
                   (recur state
                          depth
                          (concat acs (vec (rest worklist)))
                          next-worklist
                          next
                          (inc n-explored))
                   (recur state
                          depth
                          (vec (rest worklist))
                          (vec (concat next-worklist acs))
                          next
                          (inc n-explored))))))
           (recur (restart! state) depth worklist next-worklist [] (inc n-explored))))))))
