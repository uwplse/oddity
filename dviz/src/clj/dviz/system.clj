(ns dviz.system
  (:require [com.stuartsierra.component :as component]
            [system.components.endpoint :refer [new-endpoint]]
            [system.components.handler :refer [new-handler]]
            [system.components.middleware :refer [new-middleware]]
            [system.components.jetty :refer [new-web-server]]
            [system.components.jdbc :refer [new-database]]
            [dviz.routes :refer [app-routes]]
            [dviz.debugger :refer [debugger debugger-websocket-server]]
            [dviz.middleware :refer [middleware]]
            [dviz.logger :refer [logger log-sender init-logger-db]]
            [dviz.config :refer [default-config]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string])
  (:gen-class))

(defn logger-system [config]
  (if (:usage-log-url config)
    {:logger-db (new-database (:logger-db-spec config) init-logger-db)
     :logger (-> (logger (:logger-ws-port config))
                 (component/using {:db :logger-db}))
     :log-sender (-> (log-sender (:usage-log-url config))
                     (component/using {:db :logger-db}))}
    {}))

(defn app-system [config]
  (merge 
   (component/system-map
    :routes (new-endpoint app-routes)
    :middleware (new-middleware {:middleware (middleware)})
    :handler (-> (new-handler)
                 (component/using [:routes :middleware]))
    :debugger (debugger (:debugger-port config))
    :debugger-websocket-server (-> (debugger-websocket-server (:debugger-ws-port config))
                                   (component/using [:debugger]))
    :http (-> (new-web-server (:port config))
              (component/using [:handler])))
   (logger-system config)))

(def opts [[nil "--usage-log-url URL" "URL to send usage logs"]])

(defn error-msg [errors]
  (str "Error in command-line args:\n\n"
       (string/join \newline errors)))

(defn config-from-args [args]
  (let [parsed-args (parse-opts args opts)]
    {:config (:options parsed-args)
     :errors (:errors parsed-args)}))

(defn -main [& args]
  (let [cli-config (config-from-args args)]
    (if (nil? (:errors cli-config))
      (component/start (app-system (merge (default-config) (:config cli-config))))
      (println (error-msg (:errors cli-config))))))
