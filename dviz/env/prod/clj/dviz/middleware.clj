(ns dviz.middleware
  (:require [ring.middleware.defaults :refer [site-defaults wrap-defaults]]))

(defn bust-cache-response [response]
  (assoc-in response [:headers "Cache-control"] "no-store"))

(defn wrap-bust-cache [handler]
  (fn
    ([request]
     (bust-cache-response (handler request)))
    ([request respond raise]
      (handler request #(respond (bust-cache-response %)) raise))))

(defn wrap-middleware [handler]
  (-> handler
      (wrap-defaults site-defaults)
      (wrap-bust-cache)))
