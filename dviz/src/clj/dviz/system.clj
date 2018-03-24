(ns dviz.system
  (:require [com.stuartsierra.component :as component]
            [system.components.endpoint :refer [new-endpoint]]
            [system.components.handler :refer [new-handler]]
            [system.components.middleware :refer [new-middleware]]
            [system.components.jetty :refer [new-web-server]]
            [dviz.routes :refer [app-routes]]
            [dviz.debugger :refer [debugger debugger-websocket-server]]
            [dviz.middleware :refer [middleware]]
            [config.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

(def DEBUGGER-PORT 4343)
(def DEBUGGER-WEBSOCKET-PORT 5000)

(defn app-system []
  (component/system-map
   :routes (new-endpoint app-routes)
   :middleware (new-middleware {:middleware (middleware)})
   :handler (-> (new-handler)
                (component/using [:routes :middleware]))
   :debugger (debugger DEBUGGER-PORT)
   :debugger-websocket-server (-> (debugger-websocket-server DEBUGGER-WEBSOCKET-PORT)
                                  (component/using [:debugger]))
   :http (-> (new-web-server (Integer/parseInt (or (env :port) "3000")))
             (component/using [:handler]))))

(defn -main [& args]
  (component/start (app-system)))
