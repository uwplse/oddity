(ns user
  (:require [dvizlog.system :refer [app-system]]
            [com.stuartsierra.component :as component]
            [reloaded.repl :refer [system init]]
            [clojure.java.jdbc :as sql :refer [query]]
            [clj-http.client :as http]))


(defn dev-system []
  (app-system 6000))


(reloaded.repl/set-init! #(dev-system))

(def start reloaded.repl/start)
(def stop reloaded.repl/stop)
(def go reloaded.repl/go)
(def reset reloaded.repl/reset)
(def reset-all reloaded.repl/reset-all)

(defn post [body] (http/post "http://localhost:6000/log"
                             {:form-params body
                              :content-type :transit+json}))
