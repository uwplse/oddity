(ns oddity.modals
  (:require [reagent.core :as reagent]
            [baking-soda.core :as b]
            [oddity.frontend-util :refer [log toggle-logging!]]
            [goog.string :as gs]
            [goog.string.format]
            [webpack.bundle]))

;; modal-body is either nil or a function that takes a "close" function
(defonce modal-body (reagent/atom nil))

(defn modal-showing []
  (some? @modal-body))

(defn show-modal [body]
  (reset! modal-body body))

(defn modal []
  (log "rendering modal")
  (let [body @modal-body
        close (fn [] (reset! modal-body nil))]
    [b/Modal {:isOpen (some? body) :toggle close}
     (when body [body close])]))
