(ns dviz.modelchecker
  (:require
   [clojure.core.async :refer [go >!! <!! chan]]))

(defprotocol IState
  (restart! [this] "Reset the state to the beginning of time")
  (actions [this] "Possible next actions in this state")
  (run! [this action] "Run action")
  (matches? [this pred] "Does this state match a predicate?"))

(defn prefix? [a b]
  (if (<= (count a) (count b))
    (= a (take (count a) b))
    false))

(defn dfs
  ([state pred] (dfs state pred 3))
  ([state pred delta-depth]
   (dfs state pred delta-depth [] () 3))
  ([state pred delta-depth current worklist depth]
   (if (matches? state pred)
     {:result :found :trace current}
     (let [worklist (concat (map #(conj current %) (actions state)))]
       (if (empty? worklist)
         {:result :not-found}
         (let [next (first worklist)]
           (if (prefix? current next)
             ;; this is the fast path
             (do
               (doseq [a (drop (count current) next)]
                 (run! state a))
               (recur state pred delta-depth next (rest worklist) depth))
             (do
               (restart! state)
               (recur state pred delta-depth [] worklist depth)))))))))
