(ns user
  (:require [oddity.system :refer [app-system]]
            [oddity.middleware :refer [middleware]]
            [oddity.config :refer [default-config]]
            [com.stuartsierra.component :as component]
            [figwheel-sidecar.config :as fw-config]
            [figwheel-sidecar.system :as fw-sys]
            [reloaded.repl :refer [system init]]
            [prone.middleware :refer [wrap-exceptions]]
            [ring.middleware.file :refer [wrap-file]]
            [system.components.middleware :refer [new-middleware]]
            [figwheel-sidecar.repl-api :as figwheel]
            [clojure.java.jdbc :as sql :refer [query]]
            [taoensso.tufte :as p]))


(defn debug-middleware [handler debug]
  (fn
    ([request]
     (prn debug)
     (handler request))
    ([request respond raise]
     (prn debug)
     (handler request respond raise))))

(def dev-config {:usage-log-url "http://localhost:6000/log"
                 :enable-logging true :enable-debugger true :enable-traces false})

(defn dev-system []
  (assoc (app-system (merge (default-config) dev-config))
         :middleware (new-middleware
                      {:middleware (into [[wrap-file "target/dev/public"]]
                                         (middleware))})
         :figwheel-system (fw-sys/figwheel-system (fw-config/fetch-config))
         :css-watcher (fw-sys/css-watcher {:watch-paths ["resources/public/css"]})))

(reloaded.repl/set-init! #(dev-system))

(defn cljs-repl []
  (fw-sys/cljs-repl (:figwheel-system system)))

(def start reloaded.repl/start)
(def stop reloaded.repl/stop)
(def go reloaded.repl/go)
(def reset reloaded.repl/reset)
(def reset-all reloaded.repl/reset-all)
