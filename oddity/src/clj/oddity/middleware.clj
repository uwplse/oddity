(ns dviz.middleware
  (:require [ring.middleware.defaults :refer [api-defaults wrap-defaults]]))

(defn bust-cache-response [response]
  (assoc-in response [:headers "Cache-control"] "no-store"))

(defn wrap-bust-cache [handler]
  (fn
    ([request]
     (bust-cache-response (handler request)))
    ([request respond raise]
      (handler request #(respond (bust-cache-response %)) raise))))

(defn middleware []
  [[wrap-defaults api-defaults]
   wrap-bust-cache])
