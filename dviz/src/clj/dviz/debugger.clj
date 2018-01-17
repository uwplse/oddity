(ns dviz.debugger
  (:use [dviz.util])
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.data.json :as json]
            [clojure.core.async :refer [go >! <! chan]]
            [compojure.core :refer [routes GET]]
            [compojure.route :as route]
            [ring.middleware.params :as params]))

(def protocol
  (gloss/compile-frame
    (gloss/finite-frame :uint32
      (gloss/string :utf-8))
    json/write-str
    json/read-str))

(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(io/encode protocol %) out)
      s)
    (s/splice
      out
      (io/decode-stream s protocol))))

(defn start-tcp-server
  [handler port & args]
  (tcp/start-server
   (fn [s info]
     (prn s)
     (prn info)
     (apply handler (wrap-duplex-stream protocol s) info args))
    {:port port}))

(defn register [s info st]
  (prn "registering")
  
  (let [msg (s/take! s)]
    (prn "in let")
    (go (let [m @msg]
          (prn msg)
          (when-let [name (get m "name")]
            (swap! st assoc-in [:sockets name] s))
          (when-let [names (get m "names")]
            (doseq [name names]
              (swap! st assoc-in [:sockets name] s)))
          (s/put! s {:ok true})))))

(defn debugger [port]
  (let [st (atom nil)
        server (start-tcp-server register port st)]
    (swap! st assoc :server server)
    st))

(defn quit [st]
  (doseq [[_ socket] (:sockets @st)]
    (s/put! socket {:msgtype "quit"})
    (s/close! socket))
  (.close (:server @st)))

(defn send-message [st msg]
  (let [socket (get-in @st [:sockets (get msg "to")])]
    (s/put! socket msg)
    @(s/take! socket)))

(defn combine-returns [returns]
  {:responses returns})

(defn send-start [st]
  (combine-returns (doall
                    (for [[server socket] (:sockets @st)]
                      (do 
                        (s/put! socket {:msgtype "start"})
                        [server @(s/take! socket)])))))

(defn send-reset [st log]
  (send-start st)
  (doseq [msg (rest log)]
    (let [socket (get-in @st [:sockets (get msg "to")])]
      (s/put! socket msg)
      @(s/take! socket)))
  {:ok true})

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn handle-debug-msg [dbg msg]
  (let [msg (json/read-str msg)
        resp (cond
               (= "servers" (get msg "msgtype")) {:servers (keys (:sockets @dbg))}
               (= "start" (get msg "msgtype")) (send-start dbg)
               (= "reset" (get msg "msgtype")) (send-reset dbg (get msg "log"))
               :else (send-message dbg msg))]
    (json/write-str resp)))

(defn debug-handler [req]
  (prn "new connection!!!!! really for real")
  (if-let [socket (try
                    @(http/websocket-connection req)
                    (catch Exception e
                      nil))]
    (let [dbg (debugger 4343)] ; TODO try a different port if it doesn't work
      (d/loop []
        ;; take a message, and define a default value that tells us if the connection is closed
        (-> (s/take! socket ::none)
            (d/chain
             ;; first, check if there even was a message, and then get a response on another thread
             (fn [msg]
               (if (= ::none msg)
                 (d/future (quit dbg))
                 (d/future (handle-debug-msg dbg msg))))
             ;; once we have a response, write it back to the client
             (fn [msg']
               (when msg'
                 (s/put! socket msg')))
             ;; if we were successful in our response, recur and repeat
             (fn [result]
               (if result
                 (d/recur))))
            ;; if there were any issues on the far end, send a stringified exception back
            ;; and close the connection
            (d/catch
                (fn [ex]
                  (d/future (quit dbg))
                  (s/put! socket (apply str "ERROR: " ex "\n" (map str (.getStackTrace ex))))
                  (s/close! socket))))))
    non-websocket-request))

(def handler
  (params/wrap-params
    (routes
      (GET "/debug" [] debug-handler)
      (route/not-found "No such page."))))

(defn http-server [port]
  (http/start-server handler {:port port}))

(defn http-client [] (http/websocket-client "ws://localhost:5000/debug"))

