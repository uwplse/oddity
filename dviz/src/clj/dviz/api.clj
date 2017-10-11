(ns dviz.api
  (:require [compojure.api.sweet :refer :all]
            [ring.util.http-response :refer :all]))

(defn traces [] ["EXAMPLE 1" "EXAMPLE 2"])

(def api-routes
  (api
   (context "/api" []
     (GET "/traces" []
       (ok {:traces (traces)})))))
