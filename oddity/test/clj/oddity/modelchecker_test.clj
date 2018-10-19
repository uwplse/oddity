(ns oddity.modelchecker-test
  (:require [clojure.test :refer :all]
            [oddity.modelchecker :as mc]))

(defrecord CoolNumberProblem [x y]
  mc/IState
  (restart! [this] (->CoolNumberProblem 0 0))
  (actions [this] [:inc-x :inc-y])
  (run-action! [this action]
    (if (= action :inc-x)
      (->CoolNumberProblem (inc x) y)
      (->CoolNumberProblem x (inc y))))
  (matches? [this pred] (pred x y)))


(deftest dfs-number-test
  (let [res (mc/dfs (->CoolNumberProblem 0 0) (fn [x y] (and (>= x 3) (<= x y))) 6)]
    (is (= (:result res) :found))
    (is (= (:trace res) [:inc-x :inc-x :inc-x :inc-y :inc-y :inc-y]))))
