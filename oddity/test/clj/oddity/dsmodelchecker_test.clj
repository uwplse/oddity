(ns oddity.dsmodelchecker-test
  (:require [clojure.test :refer :all]
            [oddity.dsmodelchecker :refer :all]))


(deftest new-state-test
  (let [res (new-state {:responses [["A" {:set-timeouts [{:type "tA" :body "B"}]
                                          :send-messages [{:type "mA" :body "B" :to "nodeB"}]
                                          :state-updates [{:path ["field" "subfield"]
                                                           :value "value"}]}]]})]
    (is (= (:timeouts res) [{:to "A" :type "tA" :body "B"}]))
    (is (= (:messages res) [{:to "nodeB" :type "mA" :body "B" :from "A"}]))
    (is (= (:states res) {"A" {"field" {"subfield" "value"}}}))))

(deftest state-matches?-test
  (is (state-matches? {:type :node-state :node "A" :path ["field" "subfield"] :value "val"}
                      {:states {"A" {"field" {"subfield" "val"}}}}))
  (is (not (state-matches? {:type :node-state :node "A" :path ["field" "subfield"] :value "val"}
                            {:states {"A" {"field" {"subfield" "not-val"}}}})))
  (is (state-matches? {:type :node-state :node "A" :path ["field" "subfield"] :value ["val"]}
                      {:states {"A" {"field" {"subfield" ["val"]}}}})))

(deftest sort-actions-test
  (let [pred {:type :node-state :node "A"}]
    (is (= (sort-actions pred
                         [{:msgtype "timeout"} {:msgtype "msg"} {:msgtype "timeout"}])
           [{:msgtype "msg"} {:msgtype "timeout"} {:msgtype "timeout"}]))
    (is (= (sort-actions pred
                         [{:msgtype "timeout" :to "B"} {:msgtype "msg"} {:msgtype "timeout" :to "A"}])
           [{:msgtype "msg"} {:msgtype "timeout" :to "A"} {:msgtype "timeout" :to "B"}]))))
