(ns dviz.event-source)

(defprotocol IEventSource
  (next-event [this]
    "returns an event and a new EventSource, or nil"))

(defrecord StaticEventSource [evs]
  IEventSource
  (next-event [this]
    (when-let [e (first evs)]
      [e (StaticEventSource. (rest evs))])))

(defn event-source-static-example []
  (StaticEventSource. 
   [{:debug "1" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    {:debug "2" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    {:debug "3" :deliver-message {:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}}
    {:debug "4" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    {:debug "5" :send-messages [{:from 0 :to 2 :type "p1a" :body {:bal 1 :id 0}}]}
    {:debug "6" :deliver-message {:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}} :update-state [1 [[:acceptor :bal] 1]]}
    {:debug "3" :deliver-message {:from 0 :to 2 :type "p1a" :body {:bal 1 :id 0}} :update-state [1 [[:acceptor :bal] 1]]}
    ]))
