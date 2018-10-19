(ns oddity.api
  (:require [compojure.api.sweet :refer :all]
            [ring.util.http-response :refer :all]
            [clojure.java.jdbc :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :refer [input-stream]]
            [clojure.walk :refer [keywordize-keys]]))

;; TODO make this a database or something

(def example-trace
  {:name "Mutex"
   :id 0
   :servers ["0" "1"]
   :trace [{:update-state [0 [["clock"] 2]] :send-messages [{:from 0 :to 1 :type "png" :body {:clock 2}}]}
           {:update-state [0 [["req" "1"] 2]] :send-messages [{:from 0 :to 1 :type "req" :body {:clock 2}}]}
           {:update-state [1 [["clock"] 2]] :send-messages [{:from 1 :to 0 :type "png" :body {:clock 2}}]}
           {:update-state [1 [["req" "2"] 2]] :send-messages [{:from 1 :to 0 :type "req" :body {:clock 2}}]}
           {:update-state [0 [["png" "2"] 2]] :deliver-message {:from 1 :to 0 :type "png" :body {:clock 2}}}
           {:update-state [0 [["crit"] true]]}
           {:update-state [1 [["png" "1"] 2]] :deliver-message {:from 0 :to 1 :type "png" :body {:clock 2}}}
           {:update-state [1 [["crit"] true]]}]})


;; (def db {:classname   "org.sqlite.JDBC"
;;          :subprotocol "sqlite"
;;          :subname     "db/database.db"
;;          })

;; (defn create-db []
;;   (db-do-commands db
;;                   (create-table-ddl :traces [:name :text ])))

(defonce trace-db
  (atom 
   [example-trace]))

(defn deserialize [trace format]
  (keywordize-keys (json/read-str trace)))

(defn add-trace [name trace format]
  (let [{:keys [servers trace]} (deserialize trace format)]
    (swap! trace-db
           (fn [db trace] (let [id (count db)] (conj db {:name name :id id :servers servers :trace trace})))
           trace)))

(defn trace [id]
  (first (filter #(= (:id %) id) @trace-db)))

(defn traces [] @trace-db)

(def api-routes
  (api
   (context "/api" []
     (GET "/trace/:id" [id]
       (println id)
       (into (ok (trace (Integer. id)))
             {:headers {"Content-Disposition" (str "attachment; filename=trace.json")
                        "Content-Type" "application/json+transit"}}))
     (GET "/traces" []
       (ok {:traces (traces)}))
     (POST "/traces" []
       :body-params [name trace format]
       (do
         (add-trace name trace format)
         (ok {:name name}))
       ))))
