(ns oddity.dsmodelchecker-test
  (:require [clojure.test :refer :all]
            [oddity.dsmodelchecker :refer :all]
            [oddity.modelchecker :refer :all]))


(deftest new-state-test
  (let [res (new-state {:responses [["A" {:set-timeouts [{:type "tA" :body "B" :to "A"}]
                                          :send-messages [{:type "mA" :body "B" :to "nodeB"
                                                           :from "A"}]
                                          :state-updates [{:path ["field" "subfield"]
                                                           :value "value"}]}]]})]
    (is (= (:timeouts res) [{:to "A" :type "tA" :body "B"}]))
    (is (= (:messages res) [{:to "nodeB" :type "mA" :body "B" :from "A"}]))
    (is (= (:states res) {"A" {"field" {"subfield" "value"}}}))))

(deftest new-state-with-raw-state-test
  (let [res (new-state {:responses [["A" {:set-timeouts [{:type "tA" :body "B" :to "A"}]
                                          :send-messages [{:type "mA" :body "B" :to "nodeB"
                                                           :from "A"}]
                                          :states {"A" {"field" {"subfield" "value"}}
                                                   "B" {"otherfield" "othervalue"}}}]]})]
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
                         [{:deliver-timeout {:msgtype "timeout"}}
                          {:deliver-message {:msgtype "msg"}}
                          {:deliver-timeout {:msgtype "timeout"}}])
           [{:deliver-message {:msgtype "msg"}}
            {:deliver-timeout {:msgtype "timeout"}}
            {:deliver-timeout {:msgtype "timeout"}}]))
    (is (= (sort-actions pred
                         [{:deliver-timeout {:msgtype "timeout" :to "B"}}
                          {:deliver-message {:msgtype "msg"}}
                          {:deliver-timeout {:msgtype "timeout" :to "A"}}])
           [{:deliver-timeout {:msgtype "timeout" :to "A"}}
            {:deliver-message {:msgtype "msg"}}
            {:deliver-timeout {:msgtype "timeout" :to "B"}}]))))

(defn response [timeouts messages updates]
  {:set-timeouts timeouts
   :send-messages messages
   :state-updates updates})

(defn make-test-system []
  (let [st (atom nil)]
    (reify ISystemControl
      (send-message! [this message]
        (case (:msgtype message)
          "timeout" (do
                      (let [new-state (swap! st update-in [(:to message) :timeouts] inc)
                            timeouts (get-in new-state [(:to message) :timeouts])]
                        (response [] [] [{:path ["timeouts"] :value timeouts}])))
          "msg" (do
                  (let [new-state (swap! st update-in [(:to message) :pings] inc)
                        pings (get-in new-state [(:to message) :pings])]
                    (response []
                              [{:msgtype "msg" :to (if (= (:to message) "node1")
                                                     "node2" "node1")
                                :from (:to message) :type "ping" :body {}}]
                              [{:path ["pings"] :value pings}])))))
      (restart-system! [this]
          (reset! st {"node1" {:timeouts 0 :pings 0} "node2" {:timeouts 0 :pings 0}})
          {:responses [["node1" {:set-timeouts [{:type "timeout" :body {} :to "node1"}]
                                 :send-messages [{:to "node2" :type "ping" :body {}
                                                  :from "node1"}]
                                 :state-updates [{:path ["timeouts"]
                                                  :value 0}
                                                 {:path ["pings"]
                                                  :value 0}]}]
                       ["node2" {:set-timeouts [{:type "timeout" :body {} :to "node2"}]
                                 :send-messages []
                                 :state-updates [{:path ["timeouts"]
                                                  :value 0}
                                                 {:path ["pings"]
                                                  :value 0}]}]]}))))

(deftest basic-dsstate
  (let [sys (make-test-system)
        state (make-dsstate sys [])
        pred (fn [node field value] {:type :node-state :node node :path [field] :value value})]
    (is (matches? state (pred "node1" "timeouts" 0)))
    (is (not (matches? state (pred "node1" "timeouts" 1))))
    (is (= (count (actions state (pred "node1" "timeouts" 0))) 3))
    (is (:deliver-message (first (actions state (pred "node1" "timeouts" 0)))))
    (let [state (run-action! state (first (actions state (pred "node1" "timeouts" 0))))]
      (is (matches? state (pred "node2" "pings" 1)))
      (is (matches? state (pred "node1" "pings" 0)))
      (is (= (count (actions state (pred "node2" "pings" 1))) 3)))
    (let [mc-res (dfs state (pred "node1" "timeouts" 3) 3 3)]
      (is (= (:result mc-res) :found)))
    (let [mc-res (dfs state (pred "node1" "timeouts" 3) 2 2)]
      (is (= (:result mc-res) :not-found)))))

(deftest prefix-dsstate
  (let [sys (make-test-system)
        state (make-dsstate sys [{:msgtype "msg" :to "node2" :from "node1"
                                  :type "ping" :body {}}
                                 {:msgtype "timeout" :to "node1" :type "timeout" :body {}}])
        pred (fn [node field value] {:type :node-state :node node :path [field] :value value})]
    (is (matches? state (pred "node1" "timeouts" 1)))
    (is (not (matches? state (pred "node1" "timeouts" 2))))
    (is (= (count (actions state (pred "node1" "timeouts" 0))) 3))
    (is (:deliver-message (first (actions state (pred "node1" "timeouts" 0)))))
    (let [state (run-action! state (first (actions state (pred "node1" "timeouts" 0))))]
      (is (matches? state (pred "node2" "pings" 1)))
      (is (matches? state (pred "node1" "pings" 1)))
      (is (= (count (actions state (pred "node2" "pings" 1))) 3)))
    (let [mc-res (dfs state (pred "node1" "timeouts" 3) 2 2)]
      (is (= (:result mc-res) :found)))
    (let [mc-res (dfs state (pred "node1" "timeouts" 3) 1 1)]
      (is (= (:result mc-res) :not-found)))))
