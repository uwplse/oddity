(ns dvizlog.routes
  (:require
   [compojure.core :refer (POST routes)]
   [compojure.route :refer [not-found]]
   [ring.util.http-response :as resp :refer [ok]]
   [clojure.java.jdbc :as sql]))

(defn write-log [db log]
  (sql/insert! db :log
               {:userid (:userid log)
                :date (:date log)
                :git (:git log)
                :body (:body log)
                :recvd (str (java.time.LocalDateTime/now))}))

(defn api-routes [{:keys [db]}]
  (routes
   (POST "/log" req
         (try
           (println "New request")
           (prn req)
           (write-log db (:body-params req))
           (ok {:ok true})
           (catch Exception e (resp/internal-server-error {:error (str e)}))))
   (not-found (resp/not-found {:error "Not found"}))))
