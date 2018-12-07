(ns oddity.coerce
  (:require [oddity.util :refer [coerce-keys]]))

(defn coerce-timeout [t]
  (coerce-keys t [:body :type :to :raw]))

(defn coerce-message [m]
  (coerce-keys m [:body :type :to :from :raw]))

(defn coerce-state-update [u]
  (coerce-keys u [:path :value]))

(defn coerce-response [response]
  (let [response (coerce-keys response [:cleared-timeouts :set-timeouts
                                        :send-messages :state-updates])]
    {:cleared-timeouts (map coerce-timeout (:cleared-timeouts response))
     :set-timeouts (map coerce-timeout (:set-timeouts response))
     :send-messages (map coerce-message (:send-messages response))
     :state-updates (map coerce-state-update (:state-updates response))}))

(defn coerce-responses [responses]
  (let [responses (coerce-keys responses [:responses])]
    {:responses (into [] (for [[node-id response] (:responses responses)]
                           [node-id (coerce-response response)]))}))

(defn coerce-pred [p]
  (let [p (coerce-keys p [:type :node :path :value])]
    (update p :type keyword)))
