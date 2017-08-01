(ns dviz.core
    (:require [reagent.core :as reagent]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [vomnibus.color-brewer :as cb]
              [dviz.circles :as c]
              [goog.string :as gs]
              [goog.string.format]))

;; Views
;; -------------------------

(defn translate [x y]
  (gs/format "translate(%d %d)" x y))


(def state (reagent/atom {:servers ["1" "2" "3"]
                  :messages {0 [{:from 1 :to 0 :type "p1a" :body {:bal 1}}]}}))

(defn add-message [from to type body]
  (swap! state update-in [:messages to] #(vec (conj % {:from from :to to :type type :body body}))))

(defn drop-message [to id]
  (swap! state update-in [:messages to] #(vec (concat (subvec % 0 id) (subvec % (inc id))))))

(def server-circle (c/circle 400 400 200))

(defn server-angle [id]
  (+ 270 (* id (/ 360 (count (:servers @state))))))

(def server-colors [cb/Dark2-3 ; 1
                    cb/Dark2-3 ; 2
                    cb/Dark2-3 ; 3
                    cb/Dark2-4 ; 4
                    cb/Dark2-5 ; 5
                    cb/Dark2-6 ; 6
                    cb/Dark2-7 ; 7
                    cb/Dark2-8 ; 8
                    ])

(defn server-color [id]
  (let [nservers (count (:servers state))
        colors (if (< 8 nservers)
                 (nth server-colors nservers)
                 cb/Dark2-8)]
    (nth colors id)))

(defn display-message-body [message]
  [:text
   {:stroke "gray" :fill "gray"}
   (gs/format "%s %s" (:type message) (:body message))])

(def ctg (reagent/adapt-react-class (aget js/React "addons" "CSSTransitionGroup")))

(defn server-position [id]
  (let [angle (server-angle id)]
    (c/angle server-circle angle)))

(defn message [index message]
  (.log js/console (gs/format "Outer %s: %s" index message))
  (let [mouse-over (reagent/atom false)
        prev-index (atom nil)
        is-new (reagent/atom true)]
    (fn [index message]
      (if @is-new
        (reagent/next-tick #(reset! is-new false)))
      (.log js/console (gs/format "Inner %s: %s" index message))
      [:g {:transform (if @is-new
                        (let [from-pos (server-position (:from message))
                              to-pos (server-position (:to message))]
                          (translate (- (:x from-pos) (:x to-pos))
                                     (- (:y from-pos) (:y to-pos))))
                        (translate 5 (* index -40)))
           :fill (server-color (:from message))
           :stroke (server-color (:from message))
           :style {:transition "transform 0.5s ease-out"}
           ;; (if @is-new {:transform "translateY(100px)"}
                  ;;     {:transition "transform 0.5s ease-out"})
           }
       [:rect {:width 40 :height 30
               :on-mouse-over #(reset! mouse-over true)
               :on-mouse-out #(reset! mouse-over false)
               :on-click #(drop-message (:to message) index)}]
       [:text {:text-anchor "end"
               :transform (translate -10 20)}
        (:type message)]
       (if @mouse-over
         [:g {:transform (translate 50 20)}
          [display-message-body message]])])))

(defn component-map-indexed
  ([f l] (component-map-indexed f l (fn [index item] index)))
  ([f l key] 
   (doall (map-indexed (fn [index item] ^{:key (key index item)} [f index item]) l))))

(defn server [id name]
  (let [pos (server-position id)]
    [:g {:transform (translate (:x pos) (:y pos))
         :fill (server-color id)
         :stroke (server-color id)}
     [:text {:x 60} name]
     [:line {:x1 0 :x2 50 :y1 0 :y2 0 :stroke-width 10}]
     [:g {:transform (translate 0 -40)}   ; inbox
      [ctg {:transitionName "message" :component "g"
            :transitionEnterTimeout 500 :transitionLeaveTimeout 500}
       (component-map-indexed message (get-in @state [:messages id])
                              (fn [id m] [m]))]]]))

(defn home-page []
  [:div [:h2 "DVIZ"]
   [:svg {:width 800 :height 600 :style {:border "1px solid black"}}
    (component-map-indexed server (:servers @state))]])

;; -------------------------
;; Routes

(def page (reagent/atom #'home-page))

(defn current-page []
  [:div [@page]])

(secretary/defroute "/" []
  (reset! page #'home-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!
    {:nav-handler
     (fn [path]
       (secretary/dispatch! path))
     :path-exists?
     (fn [path]
       (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
