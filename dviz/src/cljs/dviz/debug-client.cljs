(ns dviz.debug-client
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [dviz.macros :refer [write-and-read-result]])
  (:require [dviz.event-source :refer [IEventSource]]
            [goog.string :as gs]
            [goog.string.format]
            [haslett.client :as ws]
            [haslett.format :as ws-fmt]
            [cljs.core.async :refer [put! take! chan <! >! timeout close!]]))

  (def DEFAULT_ID "1")

(defrecord Debugger [in out state-atom st]
  IEventSource
  (next-event [this ch action]
    (go
      (if (= (:type action) :trace)
        ;; TODO: this is very hacky
        (let [n (write-and-read-result in [st action] out)]
          (dotimes [_ n]
            (let [[res st] (<! out)]
              (put! ch [res (Debugger. in out state-atom st)]))))
        (let [[res st] (write-and-read-result in [st action] out)]
          (put! ch [res (Debugger. in out state-atom st)])))))
  (reset [this ch]
    (go
      (let [[res st] (write-and-read-result in [st {:type :reset}] out)]
        (put! ch this)))))

(defonce timeout-duration 1000)

(defn make-debugger-msg [st type m]
  (merge m {:id  (or (get st :id) DEFAULT_ID) :msgtype type}))

(defn make-msg [state action]
  (.log js/console (gs/format "Making message for action: %s" action))
  (case (:type action)
    :start {:msgtype "start" :id DEFAULT_ID}
    :timeout
    (if-let [remote-id (get (:timeout action) :remote-id)]
      (make-debugger-msg state "timeout" {:timeout-id remote-id :to (:to (:timeout action))})
      (let [{:keys [to type body]} (:timeout action)]
        (make-debugger-msg state "timeout" {:to to :type type :body body})))
    :message
    (if-let [remote-id (get (:message action) :remote-id)]
      (make-debugger-msg state "msg" {:msg-id remote-id :to (:to (:message action))}) 
      (let [{:keys [from to type body]} (:message action)]
        (make-debugger-msg state "msg" {:to to :from from :type type :body body})))
    :reset
    (make-debugger-msg state "reset" {:log (:log state)})
    :duplicate
    nil
    :drop nil))

(defn process-single-response [server-id response]
  (let [remote-id (get response "@id")
        update-states {server-id 
                       (for [{path "path" value "value"}
                             (get response "state-updates")]
                         [path value])}
        states (if (contains? response "states")
                 {server-id (get-in response ["states" server-id])}
                 {})
        set-timeouts (for [pre-timeout (get response "set-timeouts")
                           :when (= (get pre-timeout "to") server-id)
                           :let [timeout {:remote-id (get pre-timeout "@id")
                                          :to (get pre-timeout "to")
                                          :type (get pre-timeout "type")
                                          :body (get pre-timeout "body")}]]
                       (assoc timeout :actions [["Fire" {:type :timeout :timeout timeout}]]))
        clear-timeouts (for [{to "to" type "type" body "body"} (get response "cleared-timeouts")
                             :when (= to server-id)]
                         [server-id {:to to :type type :body body}])
        send-messages (for [pre-message (get response "send-messages")
                            :when (= (get pre-message "from") server-id)
                            :let [message {:remote-id (get pre-message "@id")
                                           :from server-id
                                           :to (get pre-message "to")
                                           :type (get pre-message "type")
                                           :body (get pre-message "body")}]]
                        (assoc message :actions [["Deliver" {:type :message :message message}]
                                                 ["Duplicate" {:type :duplicate :message message}]
                                                 ["Drop" {:type :drop :message message}]]))]
    {:debug "yo"
     :remote-id remote-id
     :states states
     :update-states update-states
     :set-timeouts set-timeouts
     :clear-timeouts clear-timeouts
     :send-messages send-messages}))

(defn merge-events [events]
  {:debug "merged"
   :remote-id (last (map :remote-id events))
   :states (apply merge (map :states events))
   :update-states (apply merge (map :update-states events))
   :set-timeouts (mapcat :set-timeouts events)
   :clear-timeouts (mapcat :clear-timeouts events)
   :send-messages (mapcat :send-messages events)})

(defn update-state [state msg event]
  (let [{actions :actions log :log} state
        delivered-message-action {:type :message :message (:deliver-message event)}
        dropped-message-action {:type :drop :message (:drop-message event)}
        clear-timeout-actions (set (map (fn [[target {body :body}]]
                                          {:type :timeout :timeout [target body]})
                                        (:clear-timeouts event)))
        removed-actions (conj (conj clear-timeout-actions delivered-message-action)
                              dropped-message-action)
        actions (remove #(contains? removed-actions %) actions)
        new-messages (map #(second (first (:actions %))) (conj (:send-messages event) (:duplicate-message event)))
        new-timeouts (map #(second (first (:actions %))) (:set-timeouts event))
        actions (remove nil? (concat actions new-messages new-timeouts))
        log (conj (vec log) msg)
        remote-id (or (:remote-id event) (:remote-id state))]
    {:actions actions :log log :remote-id remote-id}))

(defn make-event [action res]
  (case (:type action)
    :start
    (let [responses (get res "responses")
          servers (keys responses)
          merged (merge-events (for [[server-id response] responses]
                                 (process-single-response server-id response)))]
      ;(prn responses)
      (assoc merged :reset {:servers servers}))
    :timeout
    (let [{server-id :to} (:timeout action)]
      (process-single-response server-id res))
    :message
    (let [{:keys [from to type body]} (:message action)
          event (process-single-response to res)
          deliver-message {:from from :to to :type type :body body}]
      (assoc event :deliver-message deliver-message))
    :reset nil
    :duplicate
    (let [{:keys [from to type body remote-id]} (:message action)
          message-without-actions {:from from :to to :type type :body body :remote-id remote-id}
          message (assoc message-without-actions
                         :actions [["Deliver" {:type :message :message message-without-actions}]
                                   ["Duplicate" {:type :duplicate :message message-without-actions}]
                                   ["Drop" {:type :drop :message message-without-actions}]])]
      {:duplicate-message message :debug "duplicate"})
    :drop
    (let [{:keys [from to type body remote-id]} (:message action)
          message-without-actions {:from from :to to :type type :body body :remote-id remote-id}]
      {:drop-message message-without-actions :debug "drop"})

    ))

(defn get-action-and-res [servers trace-entry]
  (if-let [timeout (get trace-entry "deliver-timeout")]
    (let [action {:type :timeout
                  :timeout {:remote-id (get timeout "@id")
                            :to (get timeout "to")
                            :type (get timeout "type")
                            :body (get timeout "body")
                            }}
          res trace-entry]
      [action res]) ; handle timeout
    (if-let [message (get trace-entry "deliver-message")]
      (let [action {:type :message
                    :message {:remote-id (get message "@id")
                              :to (get message "to")
                              :from (get message "from")
                              :type (get message "type")
                              :body (get message "body")
                              }}
            res trace-entry]
        [action res])
      (if-let [message (get trace-entry "duplicate-message")]
        (let [action {:type :duplicate
                    :message {:remote-id (get message "@id")
                              :to (get message "to")
                              :from (get message "from")
                              :type (get message "type")
                              :body (get message "body")
                              }}
            res trace-entry]
          [action res])
        (if-let [message (get trace-entry "drop-message")]
          (let [action {:type :drop
                        :message {:remote-id (get message "@id")
                                  :to (get message "to")
                                  :from (get message "from")
                                  :type (get message "type")
                                  :body (get message "body")
                                  }}
            res trace-entry]
          [action res])
          (let [action {:type :start}
                res {"responses" (into {} (for [server servers] [server trace-entry]))}]
            [action res]))) ; handle start
     )))

(defn canonicalize-message [m]
  (if (:from m)
    (let [{:keys [from to type body]} m]
      {:from from :to to :type type :body body})
    (let [{from "from" to "to" type "type" body "body"} m]
      {:from from :to to :type type :body body})))

(defn dec-at-key [k m]
  (if k
    (update m k dec)
    m))

(defn inc-at-key [k m]
  (if k
    (update m k inc)
    m))

(defn message-diffs [trace-entry]
  [(map canonicalize-message (get trace-entry "send-messages"))
   (if-let [m (get trace-entry "deliver-message")]
     (canonicalize-message m))
   ])

(defn delivered-before-sent [trace msg]
  (loop [remaining trace]
    (if (empty? remaining) false
        (let [t (first remaining)
              [new-messages delivered-message] (message-diffs t)]
          (cond
            (= delivered-message msg) true
            (contains? new-messages msg) false
            :else (recur (rest remaining)))))))

(defn preprocess-trace [trace]
  (loop [counts {} remaining trace trace []]
    (if (empty? remaining)
      trace
      (let [t (first remaining)
            [new-messages delivered-message] (message-diffs t)
            new-counts (->> counts
                            (merge-with + (frequencies new-messages))
                            (dec-at-key delivered-message))]
        (if (and (= (new-counts delivered-message) 0)
                 (delivered-before-sent (rest remaining) delivered-message))
          (recur (inc-at-key delivered-message new-counts)
                 (rest remaining)
                 (into trace [{"duplicate-message" (get t "deliver-message")} t]))
          (recur new-counts (rest remaining) (into trace [t])))))))

(defn debug-socket [state-atom]
  (let [in (chan) out (chan)]
    (go
      (let [stream (loop []
                     (let [conn (ws/connect "ws://localhost:5000/debug" {:format ws-fmt/json})]
                       (alt!
                         conn ([c] c)
                         (timeout 500) (recur))))]
        (let [to-server (:sink stream)
              from-server (:source stream)]
          (swap! state-atom assoc :status :ready)
          (swap! state-atom assoc :started false)
          (loop []
            (alt!
              (timeout timeout-duration) (let [res (write-and-read-result to-server
                                                                          {:msgtype "servers"}
                                                                          from-server)]
                                           (swap! state-atom assoc :servers
                                                  (get-in res [DEFAULT_ID "servers"]))
                                           (swap! state-atom assoc :trace
                                                  (get-in res [DEFAULT_ID "trace"]))
                                           (recur))
              in ([[st action]]

                  (let [action
                        (cond
                          (nil? action)              (rand-nth (:actions st))
                          (= (:type action) :oldest) (first (filter (fn [a] (= (:type a) :message)) (:actions st)))
                          :else                      action)]
                    (do
                      (swap! state-atom assoc :status :processing)
                      (swap! state-atom assoc :started true)
                      (cond
                        (= (:type action) :trace)
                        (let [servers (get-in action [:trace "servers"])
                              trace (preprocess-trace (get-in action [:trace "trace"]))]
                          (.log js/console (gs/format "Replaying trace: %s" trace))
                          (>! out (count trace))
                          (loop [remaining trace state st]
                            (when-not (empty? remaining)
                              (let [t (first remaining)
                                    [action res] (get-action-and-res servers t)]
                                (let [msg (assoc (make-msg state action) :state-id (:remote-id state))
                                      event (make-event action res)
                                      state (update-state state msg event)]
                                  (.log js/console (gs/format "Replay event %s" event))
                                  (>! out [event state])
                                  (recur (rest remaining) state))))))
                        (and (= (:type action) :reset) (get st :remote-id))
                        (>! out [nil st])
                        :else
                        (let [msg (assoc (make-msg st action) :state-id (:remote-id st))
                              res (when (:msgtype msg)
                                    (write-and-read-result to-server msg from-server))
                              event (make-event action res)
                              state (update-state st msg event)]
                          (>! out [event state])))
                      (swap! state-atom assoc :status :ready)
                      (recur)))))))))
    [in out]))

(defn make-debugger [state-atom]
  (let [[in out] (debug-socket state-atom)]
    (Debugger. in out state-atom nil)))

(defn close-debugger [dbg]
  (close! (:in dbg)))
