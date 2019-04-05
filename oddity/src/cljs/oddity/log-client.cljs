(ns oddity.log-client
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [oddity.macros :refer [write-and-read-result]])
  (:require [goog.string :as gs]
            [goog.string.format]
            [haslett.client :as ws]
            [haslett.format :as ws-fmt]
            [cljs.core.async :refer [put! take! chan <! >! timeout close!]]))


(defonce session (.toString (random-uuid)))

(defn log-socket [state-atom]
  (let [in (chan)]
    (go
      (if-let [stream (loop [tries 20]
                        (let [conn (ws/connect "ws://localhost:5001/log"
                                               {:format ws-fmt/transit})]
                          (alt!
                            conn ([c] c)
                            (timeout 500) (when (> tries 0) (recur (dec tries))))))]
        (let [to-server (:sink stream)
              from-server (:source stream)]
          (let [res (write-and-read-result to-server {:type :init} from-server)]
            (if (:ok res)
              (let [userid (:userid res)]
                (swap! state-atom assoc :connected true)
                (swap! state-atom assoc :userid userid)
                (loop [userid userid]
                  (if-let [msg (<! in)]
                    (case (:type msg)
                      :register (let [userid (:userid msg)]
                                  (let [res (write-and-read-result to-server msg from-server)]
                                    (if (:ok res)
                                      (do 
                                        (swap! state-atom assoc :userid (:userid msg))
                                        (recur userid))
                                      (prn (:error res)))))
                      :unregister (do
                                    (swap! state-atom assoc :userid nil)
                                    (let [res (write-and-read-result to-server msg from-server)]
                                      (if (:ok res)
                                        (recur nil)
                                        (prn (:error res)))))
                      :log (if userid
                             (do (let [res (write-and-read-result to-server
                                                                  (assoc (assoc msg :userid userid)
                                                                         :session session)
                                                                  from-server)]
                                   (if (:ok res)
                                     (recur userid)
                                     (prn (:error res)))))
                             (recur userid))))))
              (prn (:error res)))))))
    in))

(defn log [sock msg]
  (when sock 
    (go (>! sock {:type :log :log msg}))))
