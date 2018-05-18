(ns dviz.debugger
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
            [ring.middleware.params :as params]
            [com.stuartsierra.component :as component]
            [clojure.java.shell :refer [sh]]))

(def DEFAULT_ID "1")

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
     (apply handler (wrap-duplex-stream protocol s) info args))
    {:port port}))

(defn register [s info st]
  (let [msg (s/take! s)]
    (go (let [m @msg]
          (let [id (or (get m "id") DEFAULT_ID)]
            (when-let [name (get m "name")]
              (swap! st assoc-in [:sessions id :sockets name] s))
            (when-let [trace (get m "trace")]
              (swap! st assoc-in [:sessions id :trace] trace))
            (when-let [names (get m "names")]
              (doseq [name names]
                (swap! st assoc-in [:sessions id :sockets name] s)))
            (s/put! s {:ok true}))))))

(defn quit-all-sessions [st]
  (doseq [[id session] (get @st :sessions)
          [_ socket] (get session :sockets)]
    (s/put! socket {:msgtype "quit"})
    (s/close! socket))
  (swap! st assoc :sessions {}))

(defrecord Debugger [port server state]
  component/Lifecycle

  (start [component]
    (when-not (:server component)
      (let [st (atom nil)
            server (start-tcp-server register port st)]
        (assoc component :server server :state st))))

  (stop [component]
    (when (:server component)
      (quit-all-sessions state)
      (.close server)
      (assoc component :server nil :state nil))))

(defn debugger [port]
  (map->Debugger {:port port}))

(defn st [dbg] @(get dbg :state))

(defn send-message [dbg msg]
  (let [socket (get-in (st dbg) [:sessions (get msg "id") :sockets (get msg "to")])]
    (s/put! socket (dissoc msg "id"))
    @(s/take! socket)))

(defn combine-returns [returns]
  {:responses returns})

(defn send-start [dbg id]
  (combine-returns (doall
                    (for [[server socket] (get-in (st dbg) [:sessions id :sockets])]
                      (do 
                        (s/put! socket {:msgtype "start"})
                        [server @(s/take! socket)])))))

(defn send-reset [dbg id log]
  (send-start dbg id)
  (doseq [msg (rest log)]
    (if (contains? msg "to")
      (let [socket (get-in (st dbg) [:sessions id :sockets (get msg "to")])]
        (s/put! socket msg)
        @(s/take! socket))))
  {:ok true})

(defn quit [dbg] (quit-all-sessions (:state dbg)))

(defn handle-debug-msg [dbg msg]
  (let [msg (json/read-str msg)
        resp (cond
               (= "servers" (get msg "msgtype"))
               (into {} (for [[id s] (:sessions (st dbg))]
                          [id {:servers (keys (get s :sockets)) :trace (get s :trace)}]))
               (= "start" (get msg "msgtype")) (send-start dbg (get msg "id"))
               (= "reset" (get msg "msgtype")) (send-reset dbg (get msg "id") (get msg "log"))
               (= "stviz" (get msg "msgtype"))
               (sh "/Users/ztatlock/research/dviz/stviz/log2dot.sh" :in (get msg "json-log"))
               :else (send-message dbg msg))]
    (json/write-str resp)))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn debug-handler [dbg]
  (fn  [req]
    (if-let [socket (try
                      @(http/websocket-connection req)
                      (catch Exception e
                        nil))]
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
                  (s/close! socket)))))
      non-websocket-request)))

(defn handler [dbg]
  (params/wrap-params
    (routes
      (GET "/debug" [] (debug-handler dbg))
      (route/not-found "No such page."))))

(defrecord DebuggerWebsocketServer [port debugger]
  component/Lifecycle

  (start [component]
    (when-not (:server component)
      (let [server (http/start-server (handler debugger) {:port port})]
        (assoc component :server server))))

  (stop [component]
    (when (:server component)
      (.close (:server component))
      (assoc component :server nil))))

(defn debugger-websocket-server [port]
  (map->DebuggerWebsocketServer {:port port}))
