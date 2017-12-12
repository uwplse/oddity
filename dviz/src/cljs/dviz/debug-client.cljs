(ns dviz.debug-client
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [dviz.macros :refer [write-and-read-result]])
  (:require [dviz.event-source :refer [IEventSource]]
            [goog.string :as gs]
            [goog.string.format]
            [haslett.client :as ws]
            [haslett.format :as ws-fmt]
            [cljs.core.async :refer [put! take! chan <! >! timeout close!]]))

(defrecord Debugger [in out state-atom st]
  IEventSource
  (next-event [this ch action]
    (go
      (let [[res st] (write-and-read-result in [st action] out)]
        (put! ch [res (Debugger. in out state-atom st)]))))
  (reset [this ch]
    (go
      (let [[res st] (write-and-read-result in [st {:type :reset}] out)]
        (put! ch this)))))

(defonce timeout-duration 1000)

(defn make-msg [state action]
  (case (:type action)
    :start {:msgtype "start"}
    :timeout
    (let [[server-id timeout-name] (:timeout action)]
      {:msgtype "timeout" :name server-id :timeout timeout-name})
    :message
    (let [{:keys [from to type body]} (:message action)]
      {:msgtype "msg" :name to :from from :type type :body body})
    :reset
    {:msgtype "reset" :log (:log state)}))

(defn process-single-response [server-id response]
  (let [update-states {server-id 
                       (for [{path "path" value "value"}
                             (get response "state-updates")]
                         [path value])}
        set-timeouts (for [[timeout _] (get response "timeouts")]
                       [server-id
                        {:server server-id :body timeout
                         :actions [["Fire"
                                    {:type :timeout
                                     :timeout [server-id timeout]}]]}])
        clear-timeouts (for [timeout (get response "cleared-timeouts")]
                         [server-id {:server server-id :body timeout}])
        send-messages (for [pre-message (get response "messages")
                            :let [message {:from server-id
                                           :to (get pre-message "dst")
                                           :type (get pre-message "type")
                                           :body (get pre-message "body")}]]
                        (assoc message :actions [["Deliver" {:type :message :message message}]]))]
    {:debug "yo"
     :update-states update-states
     :set-timeouts set-timeouts
     :clear-timeouts clear-timeouts
     :send-messages send-messages}))

(defn merge-events [events]
  {:debug "merged"
   :update-states (apply merge (map :update-states events))
   :set-timeouts (mapcat :set-timeouts events)
   :clear-timeouts (mapcat :clear-timeouts events)
   :send-messages (mapcat :send-messages events)})

(defn update-state [state msg event]
  (let [{actions :actions log :log} state
        delivered-message-action {:type :message :message (:deliver-message event)}
        clear-timeout-actions (set (map (fn [[target {body :body}]]
                                          {:type :timeout :timeout [target body]})
                                        (:clear-timeouts event)))
        removed-actions (conj clear-timeout-actions delivered-message-action)
        actions (remove #(contains? removed-actions %) actions)
        new-messages (map #(second (first (:actions %))) (:send-messages event))
        new-timeouts (map #(second (first (:actions (second %)))) (:set-timeouts event))
        actions (concat actions new-messages new-timeouts)
        log (conj (vec log) msg)]
    {:actions actions :log log}))

(defn make-event [action res]
  (case (:type action)
    :start
    (let [responses (get res "responses")
          servers (keys responses)
          merged (merge-events (for [[server-id response] responses]
                                 (process-single-response server-id response)))]
      (assoc merged :reset {:servers servers}))
    :timeout
    (let [[server-id _] (:timeout action)]
      (process-single-response server-id res))
    :message
    (let [{:keys [from to type body]} (:message action)
          event (process-single-response to res)
          deliver-message {:from from :to to :type type :body body}]
      (assoc event :deliver-message deliver-message))
    :reset nil
    ))

(defn debug-socket [state-atom]
  (let [in (chan) out (chan)]
    (go
      (let [stream (<! (ws/connect "ws://localhost:5000/debug" {:format ws-fmt/json}))
            to-server (:sink stream)
            from-server (:source stream)]
        (swap! state-atom assoc :status :ready)
        (swap! state-atom assoc :started false)
        (prn "hello doug")
        (loop []
            (alt!
              (timeout timeout-duration) (let [res (write-and-read-result to-server
                                                                          {:msgtype "servers"}
                                                                          from-server)]
                                           (swap! state-atom assoc :servers
                                                  (get res "servers"))
                                           (recur))
              in ([[st action]]
                  (let [action (or action (rand-nth (:actions st)))]
                    (do
                      (swap! state-atom assoc :status :processing)
                      (swap! state-atom assoc :started true)
                      (let [msg (make-msg st action)
                            res (write-and-read-result to-server msg from-server)
                            event (make-event action res)
                            state (update-state st msg event)]
                        (>! out [event state])
                        (swap! state-atom assoc :status :ready)
                        (recur)))))))))
    [in out]))

(defn make-debugger [state-atom]
  (let [[in out] (debug-socket state-atom)]
    (Debugger. in out state-atom nil)))

(defn close-debugger [dbg]
  (close! (:in dbg)))
