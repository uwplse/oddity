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
  (if (:enable-logging config)
    {:logger-db (new-database (:logger-db-spec config) init-logger-db)
     :logger (-> (logger (:logger-ws-port config))
                 (component/using {:db :logger-db}))
     :log-sender (-> (log-sender (:usage-log-url config))
                     (component/using {:db :logger-db}))}
    {}))

(defn debugger-system [config]
  (if (:enable-debugger config)
    {:debugger (debugger (:debugger-port config))
     :debugger-websocket-server (-> (debugger-websocket-server (:debugger-ws-port config))
                                    (component/using [:debugger]))}
    {}))

(defn app-system [config]
  (merge 
   (component/system-map
    :routes (new-endpoint (app-routes config))
    :middleware (new-middleware {:middleware (middleware)})
    :handler (-> (new-handler)
                 (component/using [:routes :middleware]))
    :http (-> (new-web-server (:port config))
              (component/using [:handler])))
   (debugger-system config)
   (logger-system config)))

(def opts [[nil "--usage-log-url URL" "URL to send usage logs"]
           [nil "--trace-mode" "Start in trace exploration mode"]])

(defn error-msg [errors]
  (str "Error in command-line args:\n\n"
       (string/join \newline errors)))

(defn config-from-args [args]
  (merge (default-config)
        (when-let [usage-log-url (:usage-log-url args)]
          {:enable-logging true
           :usage-log-url usage-log-url})
        (when (:trace-mode args)
          {:enable-logging false
           :enable-debugger false
           :enable-traces true})))

(defn config-from-cli [cli]
  (let [parsed-args (parse-opts cli opts)]
    {:config (config-from-args (:options parsed-args))
     :errors (:errors parsed-args)}))

(defn -main [& args]
  (let [cli-config (config-from-cli args)]
    (if (nil? (:errors cli-config))
      (component/start (app-system (:config cli-config)))
      (println (error-msg (:errors cli-config))))))
