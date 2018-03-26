(ns dvizlog.system
  (:require [com.stuartsierra.component :as component]
            [system.components.endpoint :refer [new-endpoint]]
            [system.components.handler :refer [new-handler]]
            [system.components.middleware :refer [new-middleware]]
            [system.components.jetty :refer [new-web-server]]
            [system.components.jdbc :refer [new-database]]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [clojure.java.jdbc :as sql]
            [dvizlog.routes :refer [api-routes]]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def log-schema
  [[:userid :text] [:date :text] [:recvd :text] [:git :text] [:body :text]])

(defn init-db [db]
  (sql/db-do-commands db
                      (sql/create-table-ddl :log log-schema {:conditional? true})))

(defn app-system [port]
  (component/system-map
   :db (new-database {:classname "org.sqlite.JDBC"
                      :subprotocol "sqlite"
                      :subname "database.db"}
                     init-db)
   :routes (component/using
            (new-endpoint api-routes)
            [:db])
   :middleware (new-middleware {:middleware [[wrap-defaults api-defaults]
                                             [wrap-restful-format :formats [:transit-json]]
                                             ]})
   :handler (-> (new-handler)
                (component/using [:routes :middleware]))
   :http (-> (new-web-server port)
             (component/using [:handler]))))

(def opts [["-p" "--port PORT" "Port" :parse-fn #(Integer/parseInt %) :default 6000]])

(defn -main [& args]
  (let [port (get-in (parse-opts args opts) [:options :port])]
    (component/start (app-system port))))
