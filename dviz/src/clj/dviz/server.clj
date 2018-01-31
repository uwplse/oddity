(ns dviz.server
  (:require [dviz.handler :refer [app]]
            [dviz.debugger :as debugger]
            [config.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

 (defn -main [& args]
   (let [port (Integer/parseInt (or (env :port) "3000"))]
     (debugger/start-debugger)
     (run-jetty app {:port port :join? false})))
