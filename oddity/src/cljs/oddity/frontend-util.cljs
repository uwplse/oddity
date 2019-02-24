(ns oddity.frontend-util
  (:require [goog.string :as gs]
            [goog.string.format]
            [alandipert.storage-atom :refer [local-storage]]))

(defonce logging-on (local-storage (atom true) :logging))

(defn toggle-logging! [] (swap! logging-on not))

(defn log [& args]
  (when @logging-on
    (.log js/console (apply gs/format args))))
