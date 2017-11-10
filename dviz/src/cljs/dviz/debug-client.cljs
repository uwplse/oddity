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
  (reset [this ch] (throw js/Error "Not implemented!")))

(defonce timeout-duration 1000)

(defn make-msg [state action])

(defn make-event-and-state [state action res]
  )

(defn debug-socket [state-atom]
  (let [in (chan) out (chan)]
    (go
      (let [stream (<! (ws/connect "ws://localhost:5000/debug" {:format ws-fmt/json}))
            to-server (:sink stream)
            from-server (:source stream)]
        (swap! state-atom assoc :status :ready)
        (loop []
            (alt!
              (timeout timeout-duration) (let [res (write-and-read-result to-server
                                                                          {:msgtype "servers"}
                                                                          from-server)]
                                           (swap! state-atom assoc :servers
                                                  (get res "servers"))
                                           (recur))
              in ([[st action]]
                  (if (nil? action)
                    (do (ws/close stream) (swap! state-atom assoc :status :closed))
                    (do
                      (swap! state-atom assoc :status :processing)
                      (let [res (write-and-read-result to-server (make-msg st action) from-server)]
                        (>! out (make-response st action res))
                        (swap! state-atom assoc :status :ready)
                        (recur)))))))))
    [in out]))

(defn make-debugger [state-atom]
  (let [[in out] (debug-socket state-atom)]
    (Debugger. in out state-atom nil)))

(defn close-debugger [dbg]
  (close! (:in dbg)))
