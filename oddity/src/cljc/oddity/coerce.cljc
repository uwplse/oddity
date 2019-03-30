(ns oddity.coerce
  (:require [oddity.util :refer [coerce-keys]]))

(defn coerce-timeout [t]
  (assoc (coerce-keys t [:body :type :to :raw :state-id] {:timeout-id "@id"}) :msgtype "timeout"))

(defn coerce-message [m]
  (assoc (coerce-keys m [:body :type :to :from :raw :state-id] {:msg-id "@id"}) :msgtype "msg"))

(defn coerce-message-or-timeout [m]
  (if (= (:msgtype (coerce-keys m [:msgtype])) "msg")
    (coerce-message m)
    (coerce-timeout m)))

(defn coerce-state-update [u]
  (coerce-keys u [:path :value]))

(defn coerce-response [response]
  (let [response (coerce-keys response [:cleared-timeouts :set-timeouts
                                        :send-messages :state-updates :states]
                              {:state-id "@id"})]
    {:cleared-timeouts (map coerce-timeout (:cleared-timeouts response))
     :set-timeouts (map coerce-timeout (:set-timeouts response))
     :send-messages (map coerce-message (:send-messages response))
     :state-updates (map coerce-state-update (:state-updates response))
     :states (:states response)
     :state-id (:state-id response)}))

(defn coerce-responses [responses]
  (let [responses (coerce-keys responses [:responses])]
    {:responses (into [] (for [[node-id response] (:responses responses)]
                           [node-id (coerce-response response)]))}))

(defn coerce-pred [p]
  (let [p (coerce-keys p [:type :node :path :value])]
    (update p :type keyword)))
