(ns dviz.core
    (:require [reagent.core :as reagent]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [vomnibus.color-brewer :as cb]
              [dviz.circles :as c]
              [dviz.event-source :as event-source]
              [goog.string :as gs]
              [goog.string.format]
              [cljsjs.react-transition-group]
              [datafrisk.core :as df]))

;; Views
;; -------------------------

(defn translate [x y]
  (gs/format "translate(%d %d)" x y))


(def events (reagent/atom (event-source/event-source-static-example)))
(def state (reagent/atom {:servers [ "1" "2" "3"]}))
(def inspect (reagent/atom nil))
(def message-id-counter (reagent/atom 0))

(defn add-message [m]
  (.log js/console "adding message")
  (let [id (swap! message-id-counter inc)
        m (merge m {:id id})]
    (swap! state update-in [:messages (:to m)] #(vec (conj % m)))))

(defn update-server-state [id path val]
  (swap! state assoc-in (concat [:server-state id] path) val))

(defn remove-one [pred coll]
  (when-let [x (first coll)]
    (if (pred x)
      (rest coll)
      (cons x (remove-one pred (rest coll))))))

(defn fields-match [fields m1 m2]
  (every? #(= (get m1 %) (get m2 %)) fields))

(defn drop-message [message]
  (swap! state update-in [:messages (:to message)] #(vec (remove-one (partial fields-match [:from :to :type :body] message) %))))

(defn do-next-event []
  (when-let [[ev evs] (event-source/next-event @events)]
    ; process delivered messages
    (when-let [m (:deliver-message ev)]
      (drop-message m))
    ;process send messages
    (when-let [ms (:send-messages ev)]
      (.log js/console (gs/format "adding messages %s" ms))
      (doseq [m ms] (add-message m)))
    (when-let [[id & updates] (:update-state ev)]
      (doseq [[path val] updates] (update-server-state id path val)))
    (when-let [debug (:debug ev)]
      (.log js/console (gs/format "Processing event: %s %s" debug ev)))
    (reset! events evs)))

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

(def transition-group (reagent/adapt-react-class js/ReactTransitionGroup.TransitionGroup))

(defn server-position [id]
  (let [angle (server-angle id)]
    (c/angle server-circle angle)))

(defn message [index message status]
  (.log js/console "Outer message called")
  (let [mouse-over (reagent/atom false)]
    (fn [index message status]
      (.log js/console (gs/format "Inner %s: %s %s" index message status))
      [:g {:transform
           (case status
             :new (let [from-pos (server-position (:from message))
                        to-pos (server-position (:to message))]
                    (translate (- (:x from-pos) (- (:x to-pos) 80))
                               (- (:y from-pos) (:y to-pos))))
             :stable (translate 5 (* index -40))
             :deleted (translate 50 0))
           :fill (server-color (:from message))
           :stroke (server-color (:from message))
           :style {:transition "transform 0.5s ease-out"}
           }
       [:rect {:width 40 :height 30
               :on-mouse-over #(reset! inspect message)
               :on-click #(drop-message message )}]
       [:text {:text-anchor "end"
               :transform (translate -10 20)}
        (:type message)]])))


(def message-wrapper
  (reagent/create-class
   {:get-initial-state (fn [] {:status  :new})
    :component-will-appear (fn [cb]
                             (this-as this
                               (reagent/replace-state this {:status :stable})
                               (cb)))
    :component-will-enter (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :stable})
                              (cb)))

    :component-will-leave (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :deleted})
                              (js/setTimeout cb 500)))
    :display-name "message-wrapper"
    :reagent-render
    (fn [index m]
      [message index m (:status (reagent/state (reagent/current-component)))])}))


(defn component-map-indexed
  ([f l] (component-map-indexed f l (fn [index item] index)))
  ([f l key] 
   (doall (map-indexed (fn [index item] ^{:key (key index item)} [f index item]) l))))

(defn server [id name]
  (let [pos (server-position id)]
    [:g {:transform (translate (:x pos) (:y pos))
         :fill (server-color id)
         :stroke (server-color id)}
     [:text {:x -20} name]
     [:line {:x1 -35 :x2 -35 :y1 -40 :y2 40 :stroke-dasharray "5,5"}]
     [:image {:xlinkHref "images/server.png" :x 0 :y -10 :width 50
              :on-mouse-over #(reset! inspect (get-in @state [:server-state id]))}]
     [:line {:x1 -100 :x2 -50 :y1 0 :y2 0 :stroke-width 10}]
     [:g {:transform (translate -100 -40)}   ; inbox
      [transition-group {:component "g"}
       (doall (map-indexed (fn [index m] ^{:key m} [message-wrapper index m])
                           (get-in @state [:messages id])))]]]))

(defn next-event-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (do-next-event))}
       "Next event"])))


(defn reset-events-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (reset! events (event-source/event-source-static-example)))}
       "Reset events"])))


(defn inspector []
  [df/DataFriskView @inspect])

(defn home-page []
  [:div
   [:h2 "DVIZ"]
   [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
          :width 800 :height 600 :style {:border "1px solid black"}}
    (component-map-indexed server (:servers @state))]
   [inspector]
   [:br]
   [next-event-button]
   [reset-events-button]
   ])

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
