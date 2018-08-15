(ns dviz.routes
  (:require [compojure.core :refer [GET routes]]
            [compojure.route :refer [not-found resources]]
            [hiccup.page :refer [include-js include-css html5]]
            [config.core :refer [env]]
            [dviz.config :refer [client-config]]))

(def mount-target
  [:div#app
   [:h3 "Loading..."]])

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css (if (env :dev) "/css/site.css" "/css/site.min.css"))
   (include-css "/css/fontawesome-all.min.css")])

(defn loading-page [config]
  (html5
    (head)
    [:body {:class "body-container"}
     [:div#config {:data-config (pr-str (client-config config))}]
     mount-target
     (include-js "/js/app.js")]))

(defn app-routes [config]
  (fn [endpoint]
    (routes
     (GET "/" [] (loading-page config))
     (GET "/about" [] (loading-page config))
     
     (resources "/")
     (not-found "Not Found"))))
