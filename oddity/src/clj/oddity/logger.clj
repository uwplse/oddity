(ns oddity.logger
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [clojure.core.async :refer [go >! <! chan timeout close! alt!]]
            [compojure.core :refer [routes GET]]
            [compojure.route :as route]
            [ring.middleware.params :as params]
            [com.stuartsierra.component :as component]
            [clojure.java.jdbc :as sql]
            [cognitect.transit :as transit]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clj-http.client :as http-client]))

(defn string->stream
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))

(defn read-transit [s]
  (-> s
      (string->stream)
      (transit/reader :json)
      (transit/read)))

(defn write-transit [obj]
  (let [s (java.io.ByteArrayOutputStream.)
        w (transit/writer s :json)]
    (transit/write w obj)
    (.toString s)))

(defn wrap-transit [sock]
  (let [out (s/stream)]
    (s/connect
     (s/map write-transit out) sock)
    (s/splice out
              (s/map read-transit sock))))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn get-user-id [db]
  (:userid (first (sql/query db "select * from user limit 1"))))

(defn register-user [db userid]
  (sql/insert! db :user {:userid userid}))

(defn unregister-user [db]
  (sql/delete! db :user []))

(defn write-log [db userid log git]
  (sql/insert! db :log {:userid userid
                        :date (str (java.time.LocalDateTime/now))
                        :body (write-transit log)
                        :sent 0
                        :git git}))

(defn handle-log-msg [{:keys [db git]} msg]
  (case (:type msg)
    :init {:ok true :userid (get-user-id db)}
    :register (do (register-user db (:userid msg)) {:ok true})
    :unregister (do (unregister-user db) {:ok true})
    :log (do (write-log db (:userid msg) (:log msg) git) {:ok true})))

(defn log-handler [lgr]
  (fn  [req]
    (if-let [raw-socket (try
                          @(http/websocket-connection req)
                          (catch Exception e
                            nil))
             ]
      (let [socket (wrap-transit raw-socket)]
        (d/loop []
          ;; take a message, and define a default value that tells us if the connection is closed
          (-> (s/take! socket ::none)
              (d/chain
               ;; first, check if there even was a message, and then get a response on another thread
               (fn [msg]
                 (d/future (handle-log-msg lgr msg)))
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
                    (s/put! socket {:error (apply str "ERROR: " ex "\n" (map str (.getStackTrace ex)))})
                    (s/close! socket))))))
      non-websocket-request)))

(defn handler [lgr]
  (params/wrap-params
   (routes
    (GET "/log" [] (log-handler lgr))
    (route/not-found "No such page."))))

(defn logger-websocket-server [lgr]
  (http/start-server (handler lgr) {:port (:port lgr)}))

(defrecord Logger [logger-ws-port db server]
  component/Lifecycle

  (start [component]
    (when-not (:server component)
      (let [git (try (string/trim (:out (shell/sh "git" "rev-parse" "HEAD")))
                     (catch Exception e ""))
            server (logger-websocket-server {:port logger-ws-port :db db :git git})]
        (assoc component :server server))))

  (stop [component]
    (when (:server component)
      (.close server)
      (assoc component :server nil))))

(defn logger [logger-ws-port]
  (map->Logger {:logger-ws-port logger-ws-port}))

(def log-schema
  [[:userid :text] [:date :text] [:git :text] [:body :text] [:sent :integer]])

(def user-schema [[:userid :text]])

(defn init-logger-db [db]
  (sql/db-do-commands db
                      [(sql/create-table-ddl :log log-schema {:conditional? true})
                       (sql/create-table-ddl :user user-schema {:conditional? true})]))

(defn send-to-server [log-url db]
  (sql/execute! db "UPDATE log SET sent = 1 WHERE sent = 0")
  (let [to-send (sql/query db "SELECT * FROM log WHERE sent = 1")
        res 
        (try
          (doseq [log to-send]
            (http-client/post log-url
                              {:form-params log
                               :content-type :transit+json}))
          true
          (catch Exception e false))]
    (if res
      (sql/execute! db "DELETE FROM log WHERE sent = 1"))))

(defn log-sender-thread [log-url db]
  (let [ch (chan)]
    (go
      (loop [] 
        (alt!
          (timeout (* 30 1000))
          (do (send-to-server log-url db) (recur))
          ch nil)))))

(defrecord LogSender [log-url db sender]
  component/Lifecycle

  (start [component]
    (when-not sender
      (let [sender (log-sender-thread log-url db)]
        (assoc component :sender sender))))

  (stop [component]
    (when sender
      (close! sender)
      (assoc component :sender nil))))

(defn log-sender [log-url]
  (map->LogSender {:log-url log-url}))
