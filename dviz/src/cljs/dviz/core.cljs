(ns dviz.core
  (:require [reagent.core :as reagent]
            [secretary.core :as secretary :include-macros true]
            [accountant.core :as accountant]
            [vomnibus.color-brewer :as cb]
            [dviz.circles :as c]
            [dviz.event-source :as event-source]
            [dviz.util :refer [remove-one]]
            [dviz.sim]
            [dviz.trees :as trees]
            [dviz.paxos :refer [paxos-sim]]
            [goog.string :as gs]
            [goog.string.format]
            [cljsjs.react-transition-group]
            [datafrisk.core :as df]))

;; Views
;; -------------------------

(defn translate [x y]
  (gs/format "translate(%d %d)" x y))


(defonce events (reagent/atom (event-source/event-source-static-example)))
(defonce state (reagent/atom nil))
(defonce event-history (reagent/atom (trees/leaf {:state @state :events @events})))
(defonce selected-event-path (reagent/atom []))
(defonce inspect (reagent/atom nil))

(defn add-message [m]
  (.log js/console "adding message")
  (let [id (:message-id-counter (swap! state update-in [:message-id-counter] inc))
        m (merge m {:id id})]
    (swap! state update-in [:messages (:to m)] #(vec (conj % m)))))

(defn update-server-state [id path val]
  (swap! state (fn [s]
                 (assoc-in s (concat [:server-state id] path) val))))

(defn update-server-log [id updates]
  (swap! state update-in [:server-log id] #(vec (conj % updates))))

(defn fields-match [fields m1 m2]
  (every? #(= (get m1 %) (get m2 %)) fields))

(defn drop-message [message]
  (swap! state update-in [:messages (:to message)] #(vec (remove-one (partial fields-match [:from :to :type :body] message) %))))

(defn index-of [l v]
  (let [i (.indexOf l v)]
    (if (>= i 0) i nil)))

(defn do-next-event []
  (when-let [[ev evs] (event-source/next-event @events)]
    (println ev)
    ;; process debug
    (when-let [debug (:debug ev)]
      (.log js/console (gs/format "Processing event: %s %s" debug ev)))
    ;; process reset
    (when-let [reset (:reset ev)]
      (println reset)
      (doall (for [[k v] reset]
               (do 
                 (println k)
                 (swap! state assoc k v)))))
    ;; process delivered messages
    (when-let [m (:deliver-message ev)]
      (drop-message m))
    ;; process state updates
    (when-let [[id & updates] (:update-state ev)]
      (doseq [[path val] updates] (update-server-state id path val))
      (update-server-log id updates))
    ;; process send messages
    (when-let [ms (:send-messages ev)]
      (.log js/console (gs/format "adding messages %s" ms))
      (doseq [m ms] (add-message m)))
    ;; add event to history
    (let [new-event-for-history {:state @state :events evs}]
      (let [next-events (map trees/root (trees/children (trees/get-path @event-history @selected-event-path)))]
        (println "next events")
        (println next-events)
        (if-let [next-event-index (index-of next-events new-event-for-history)]
          (swap! selected-event-path conj next-event-index)
          (do
            (println "here")
            (let [[new-event-history new-selected-event-path]
                  (trees/append-path @event-history @selected-event-path new-event-for-history)]
              (reset! event-history new-event-history)
              (reset! selected-event-path (vec new-selected-event-path)))))))
    (reset! events evs)))

(def server-circle (c/circle 400 300 150))

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

(defn server-log-entry-line [index [path val]]
  [:tspan {:x "0" :dy "-1.2em"} (gs/format "%s = %s" (clojure.string/join "." (map name path)) val)])

(defn component-map-indexed
  ([f l] (component-map-indexed f l (fn [index item] index)))
  ([f l key] 
   (doall (map-indexed (fn [index item] ^{:key (key index item)} [f index item]) l))))

(defn server-log-entry [updates status]
  (fn [updates status]
    [:g {:transform
         (case status
           :new (translate 0 0)
           :stable (translate 0 -80))
         :style {:opacity (case status :new "1.0" :stable "0.0")
                 :transition "all 3s ease-out"
                 :transition-property "transform, opacity"}
         }
     [:text
      (component-map-indexed server-log-entry-line updates)]]))

(def server-log-entry-wrapper
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
    :display-name "server-log-wrapper"
    :reagent-render
    (fn [upd]
      [server-log-entry upd (:status (reagent/state (reagent/current-component)))])}))


(defn server [id name]
  (let [pos (server-position id)
        server-state (get-in @state [:server-state id])]
    [:g {:transform (translate (:x pos) (:y pos))
         :fill (server-color id)
         :stroke (server-color id)}
     [:text {:x -20} name]
     [:line {:x1 -35 :x2 -35 :y1 -40 :y2 40 :stroke-dasharray "5,5"}]
     [:image {:xlinkHref "images/server.png" :x 0 :y -10 :width 50
              :on-mouse-over #(reset! inspect server-state)}]
     [:line {:x1 -100 :x2 -50 :y1 0 :y2 0 :stroke-width 10}]
     [:g {:transform (translate -100 -40)}   ; inbox
      [transition-group {:component "g"}
       (doall (map-indexed (fn [index m] ^{:key m} [message-wrapper index m])
                           (get-in @state [:messages id])))]]
     [:g {:transform (translate 0 -40)}   ; log
      [transition-group {:component "g"}
       (doall (map-indexed (fn [index upd] ^{:key index} [server-log-entry-wrapper upd])
                           (get-in @state [:server-log id])))]]]))

(defn history-move [path]
  (let [{new-state :state new-events :events} (trees/root (trees/get-path @event-history path))]
    (reset! state new-state)
    (reset! events new-events)
    (reset! selected-event-path path)))

(defn history-view-event [path [x y] event parent-position]
  [:g {:fill "black" :stroke "black"}
   (when-let [[parent-x parent-y] parent-position]
     [:line {:x1 parent-x :x2 x :y1 parent-y :y2 y :stroke-width 5 :stroke-dasharray "5,1"}])
   [:g {:transform (translate x y)}
    [:circle {:cx 0 :cy 0 :r 20
              :on-click #(history-move path)
              :stroke (if (= path @selected-event-path) "red" "black")
              :stroke-width 5}]]])

(defn history-view []
  (let [xstart (reagent/atom 0)]
    (fn []
      [:div
       [:button {:on-click #(swap! xstart - 20)
                 :style {:width 10 :margin-right 14 :text-align "center"}} "<"]
       [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
              :width 750 :height 100
              :viewBox (gs/format "%d %d %d %d" @xstart 0 750 100)
              :style {:border "1px solid black"}}
        [:g {:transform (translate 50 50)}
         (let [layout (trees/layout @event-history 75 -50)]
           (doall
            (for [{:keys [position value path parent]} layout]
              ^{:key [path value]} [history-view-event path position value parent])))]]
       [:button {:on-click #(swap! xstart + 20)
                 :style {:width 10 :margin-left 14 :text-align "center"}} ">"]])))

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
       "Static events"])))

(defn paxos-events-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (reset! events (paxos-sim 3)))}
       "Paxos events"])))


(defn inspector []
  [df/DataFriskView @inspect])

(defn home-page []
  [:div
   [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
          :width 800 :height 600 :style {:border "1px solid black"}
          :viewBox "0 0 800 600"}
    (component-map-indexed server (:servers @state))]
   [inspector]
   [:br]
   [next-event-button]
   [reset-events-button]
   [paxos-events-button]
   [history-view]])

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
