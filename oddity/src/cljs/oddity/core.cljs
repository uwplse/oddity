(ns oddity.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [secretary.core :as secretary :include-macros true]
            [accountant.core :as accountant]
            [vomnibus.color-brewer :as cb]
            [oddity.circles :as c]
            [oddity.modals :as modals]
            [baking-soda.core :as b]
            [oddity.event-source :as event-source]
            [oddity.util :refer [remove-one differing-paths fields-match]]
            [oddity.sim]
            [oddity.trees :as trees]
            [oddity.paxos :refer [paxos-sim]]
            [oddity.debug-client :refer [make-debugger]]
            [oddity.log-client :as log]
            [oddity.frontend-util :refer [log toggle-logging!]]
            [goog.string :as gs]
            [goog.string.format]
            [cljsjs.react-transition-group]
            [datafrisk.core :as df]
            [ajax.core :refer [GET POST]]
            [cognitect.transit :as t]
            [cljsjs.filesaverjs]
            [cljs.core.async :refer [put! take! chan <! >! close!]]
            [clojure.browser.dom :refer [get-element]]
            [alandipert.storage-atom :refer [local-storage]]
            [haslett.client :as ws]
            [haslett.format :as ws-fmt]
            [webpack.bundle]
            [instaparse.core :as insta :refer-macros [defparser]]
            [cljs.core.match :refer-macros [match]]))

(def DEBUG false)

(defn delta-from-wheel [zoom e]
  (let [raw (.-deltaY e)
        sign (if (< raw 0) -1 1)
        magnitude (js/Math.abs raw)
        scale .05]
    (if-not (= raw 0)
      (* scale (* sign (js/Math.log magnitude)))
      0)))

(defn debug-render [s & rest]
  (if DEBUG (apply log s rest)))

(defn get-config [k]
  (-> (.getElementById js/document "config")
      (.getAttribute "data-config")
      (cljs.reader/read-string)
      (get k)))

;; Views
;; -------------------------

(defn translate [x y]
  (gs/format "translate(%d %d)" x y))

(defonce events (reagent/atom nil))
(defonce state (reagent/atom nil))
(defonce server-positions (local-storage (reagent/atom {}) :server-positions))
(defonce event-history (reagent/atom nil))
(defonce selected-event-path (reagent/atom []))
(defonce inspect (reagent/atom nil))
;; TODO: get rid of this hack
(defonce message-extra-add-drop-data (atom nil))
(defonce main-window-zoom (reagent/atom 1))
(defonce main-window-xstart (reagent/atom 0))
(defonce main-window-ystart (reagent/atom 0))

(defonce main-window-ratio (reagent/atom 1))

(defonce log-state (reagent/atom nil))
(defonce logger (if (get-config :enable-logging) (log/log-socket log-state)))


(defn non-propagating-event-handler [f]
  (fn [e] (f e) (.preventDefault e) (.stopPropagation e) false))

(defn add-message [m]
  (let [id (:message-id-counter (swap! state update-in [:message-id-counter] inc))
        m (merge m {:id id})]
    (swap! state update-in [:messages (:to m)] #(vec (conj % m)))))

(defn set-timeout [t]
  (let [id (:timeout-id-counter (swap! state update-in [:timeout-id-counter] inc))
        t (merge t {:id id})]
    ;(log "Adding %s to %s" t (get-in @state [:timeouts (:to t)]))
    (swap! state update-in [:timeouts (:to t)] #(vec (conj % t)))))

(defn clear-timeout [id t]
  (log "Removing %s from %s" t (get-in @state [:timeouts id]))
  (swap! state update-in [:timeouts id]
         #(vec (remove-one
                (if (:unique-id t)
                  (partial fields-match [:unique-id] t)
                  (partial fields-match [:to :type :body :raw] t)) %)))
  (log "did the thing"))

(defn update-server-state [id path val]
  (swap! state (fn [s]
                 (assoc-in s (concat [:server-state id] path) val))))

(defn reset-server-state [id new-state]
  (swap! state (fn [s]
                 (assoc-in s (concat [:server-state id]) new-state))))

(defn update-server-log [id updates]
  (swap! state update-in [:server-log id] #(vec (conj % updates))))

(defn deliver-message [message]
  (swap! message-extra-add-drop-data assoc (select-keys message [:from :to :type :body]) :deliver)
  (swap! state update-in [:messages (:to message)] #(vec (remove-one (partial fields-match [:from :to :type :body :raw] message) %))))

(defn drop-message [message]
  (swap! message-extra-add-drop-data assoc (select-keys message [:from :to :type :body]) :drop)
  (swap! state update-in [:messages (:to message)] #(vec (remove-one (partial fields-match [:from :to :type :body] message) %))))

(defn duplicate-message [message]
  ;; TODO: make this better
  (swap! message-extra-add-drop-data assoc (select-keys message [:from :to :type :body]) :dup)
  (add-message message))

(defn send-message [message]
  ;; TODO: make this better
  (swap! message-extra-add-drop-data assoc (select-keys message [:from :to :type :body]) :send)
  (add-message message))


(defn index-of [l v]
  (let [i (.indexOf l v)]
    (if (>= i 0) i nil)))

(defonce next-event-channel (chan))

(defn handle-state-updates [id updates]
  (doseq [[path val] updates] (update-server-state id path val))
  (update-server-log id updates))

(defn next-event-loop []
  (log "starting event loop (you'd better only see this once!)")
  (go-loop []
    (let [[ev evs] (<! next-event-channel)]
      ;; process debug
      (when-let [debug (:debug ev)]
        (log "Processing event: %s %s" debug ev))
      ;; process reset
      (when-let [reset (:reset ev)]
        (doall (for [[k v] reset]
                 (do 
                   (swap! state assoc k v)))))
      ;; process delivered messages
      (when-let [m (:deliver-message ev)]
        (deliver-message m))
      (when-let [m (:drop-message ev)]
        (drop-message m))
      (when-let [m (:duplicate-message ev)]
        (duplicate-message m))
      ;; process state updates
      (when-let [[id & updates] (:update-state ev)]
        (handle-state-updates id updates))
      ;; TODO unify these
      (when-let [state-updates (:update-states ev)]
        (doseq [[id updates] state-updates]
          (handle-state-updates id updates)))
      ;; process state dumps
      (when-let [state-dumps (:states ev)]
        (doseq [[id new-state] state-dumps]
          (let [id (name id)]
            ;(log "Updating server %s to state %s" id new-state)
            (let [updates (differing-paths (get-in @state [:server-state id]) new-state)]
              (reset-server-state id new-state)
              (update-server-log id updates)))))
      ;; process send messages
      (when-let [ms (:send-messages ev)]
        (doseq [m ms] (send-message m)))
      ;; process cleared timeouts
      (when-let [ts (:clear-timeouts ev)]
        ;(prn "clearing timeout")
        (doseq [[id t] ts] (clear-timeout id t)))
      ;; process new timeouts
      (when-let [ts (:set-timeouts ev)]
        ;(prn "setting timeout")
        (doseq [t ts] (set-timeout t)))
      ;; add event to history
      (let [new-event-for-history {:state @state :events evs :message-extra-add-drop-data @message-extra-add-drop-data}]
        (let [next-events (map trees/root (trees/children (trees/get-path @event-history @selected-event-path)))]
          (if-let [next-event-index (index-of next-events new-event-for-history)]
            (swap! selected-event-path conj next-event-index)
            (do
              (let [[new-event-history new-selected-event-path]
                    (if (nil? @event-history)
                      [(trees/leaf new-event-for-history) []]
                      (trees/append-path @event-history
                                         @selected-event-path new-event-for-history))]
                (reset! event-history new-event-history)
                (reset! selected-event-path (vec new-selected-event-path))
                (log/log logger {:path @selected-event-path :state @state}))))))
      (reset! events evs)
      (recur))))

(defn do-next-event [action]
  (log/log logger {:action action})
  (event-source/next-event @events next-event-channel action))

(def server-circle (c/circle 400 300 150))

(defn server-angle [state index]
  (+ 270 (* index (/ 360 (count (:servers state))))))

(def server-colors [cb/Dark2-3 ; 1
                    cb/Dark2-3 ; 2
                    cb/Dark2-3 ; 3
                    cb/Dark2-4 ; 4
                    cb/Dark2-5 ; 5
                    cb/Dark2-6 ; 6
                    cb/Dark2-7 ; 7
                    cb/Dark2-8 ; 8
                    ])

(defn server-color [state id]
  (let [nservers (count (:servers state))
        colors-index (- (min (count server-colors) nservers) 1)
        colors (nth server-colors colors-index)
        index (mod (.indexOf (:servers state) id) (count colors))]
    (nth colors index)))

(def transition-group (reagent/adapt-react-class js/ReactTransitionGroup.TransitionGroup))
(def transition (reagent/adapt-react-class js/ReactTransitionGroup.Transition))
(def json-tree (aget js/ReactJsonTree "default"))

(defn server-position [state id]
  (if-let [pos (get @server-positions id)]
    pos
    (let [index (.indexOf (:servers state) id)
          angle (server-angle state index)]
      (c/angle server-circle angle))))

(defn reset-server-positions [state]
  (swap! server-positions
         #(reduce (fn [m k] (dissoc m k)) % (:servers state))))

(defn message [status state index message inbox-loc static]
  (let [clicked (reagent/atom false) ; to prevent duplicate clicks
        ]
    (fn [status state index message inbox-loc static]
      (debug-render "message")
      (let [[inbox-loc-x inbox-loc-y] inbox-loc]
        [:g {:transform
             (case status
               "exited"
               (if (= (get @message-extra-add-drop-data (select-keys message [:from :to :type :body]))
                      :send)
                 (let [from-pos (server-position state (:from message))
                       to-pos (server-position state (:to message))]
                               (translate (- (:x from-pos) (- (:x to-pos) 80))
                                          (- (:y from-pos) (:y to-pos))))

                 (translate 5 0))
               ("entering" "entered") (translate 5 (* index -40))
               "exiting"
               (if (= (get @message-extra-add-drop-data (select-keys message [:from :to :type :body]))
                      :deliver)
                 (translate 50 0)
                 (translate -100 (* index -40))))
             :fill (server-color state (:from message))
             :stroke (server-color state (:from message))
             :style {:transition (when (not static) "transform .5s ease-out")}
             }
         [:rect {:width 40 :height 30
                 :on-context-menu
                 (when (and (not static) (not @clicked))
                   (non-propagating-event-handler (fn [])))
                 :on-mouse-down
                 (when (and (not static) (not @clicked))
                   (fn [e]
                     (case (.-button e)
                       2 (do (reset! inspect {:x (+ inbox-loc-x 5) :y (+ inbox-loc-y (* index -40))
                                              :value (:body message)
                                              :actions (:actions message)})
                             (.preventDefault e) (.stopPropagation e))
                       0 (when-let [[name action] (first (:actions message))]
                           (reset! clicked true)
                           (do-next-event action)))))}]
         [:text {:style {:pointer-events "none" :user-select "none"} :text-anchor "end"
                 :transform (translate -10 20)}
          (:type message)]]))))



(defn timeout [status state index timeout inbox-loc static]
  (let [clicked (reagent/atom false)]
    (fn [status state index timeout inbox-loc static]
      (debug-render "timeout")
      (let [[inbox-loc-x inbox-loc-y] inbox-loc]
        [:g {:transform
             (case status
               ("exiting" "exited") (translate 50 0)
               ("entering" "entered") (translate 5 (* index -40)))
             :fill (server-color state (:to timeout))
             :stroke (server-color state (:to timeout))
             :style {:transition (when (not static) "transform 0.5s ease-out")}
             }
         [:g {:on-context-menu
              (when (and (not static) (not @clicked))
                (non-propagating-event-handler (fn [])))
              :on-mouse-down
              (when (and (not static) (not @clicked))
                (fn [e]
                  (case (.-button e)
                    2 (do (reset! inspect {:x (+ inbox-loc-x 5) :y (+ inbox-loc-y (* index -40))
                                           :value (:body timeout)
                                           :actions (:actions timeout)})
                          (.preventDefault e) (.stopPropagation e))
                    0 (when-let [[name action] (first (:actions timeout))] (do-next-event action)))))}
          [:rect {:width 40 :height 30}]
          [:text {:class "fas" :style {:pointer-events "none" :user-select "none" :fill "white"} :x 20 :y 15 :text-anchor "middle" :dominant-baseline "middle"} "\uf252"]]
         [:text {:style {:pointer-events "none" :user-select "none"}
                 :text-anchor "end"
                 :transform (translate -10 20)}
          (:type timeout)]]))))


(defn transition-wrapper [{:keys [in component component-args duration]}]
  [transition {:in in
               :timeout duration
               :unmountOnExit true}
   (fn [status]
     (reagent/as-element
      (into [component status] (js->clj component-args))))])

(defn path-component [c]
  (if (keyword? c) (str (name c)) (str c)))

(defn component-map-indexed [el f l & args]
  (into el (map-indexed (fn [index item] (with-meta (vec (concat [f] args [index item]))
                                           {:key [index item]})) l)))

(defn timeouts-and-messages [state server-id inbox-pos static]
  (let [timeouts (get-in state [:timeouts server-id])
        messages (get-in state [:messages server-id])
        ntimeouts (count timeouts)]
    (debug-render "timeouts-and-messages")
    [:g
     (if static
       (doall (map-indexed (fn [index t] ^{:key t} [timeout "entered" state index t inbox-pos static])
                           timeouts))
       [transition-group {:component "g" :className "dougsclass"}
        (doall (map-indexed (fn [index t]
                              (let [child (reagent/reactify-component transition-wrapper)]
                                (reagent/create-element child
                                                        #js {:key t
                                                             :component timeout
                                                             :component-args [state index t inbox-pos static]
                                                             :duration 500})))
                            timeouts))]
       )
     (if static
       (doall (map-indexed (fn [index m] ^{:key m}
                             [message "entered" state (+ index ntimeouts) m inbox-pos static])
                           messages))
       [transition-group {:component "g"}
        (doall (map-indexed (fn [index m]
                              (let [child (reagent/reactify-component transition-wrapper)]
                                (reagent/create-element child
                                                        #js {:key m
                                                             :component message
                                                             :component-args [state (+ index ntimeouts) m inbox-pos static]
                                                             :duration 500})))
                            messages))]
       )
     ]))

(defn server [state static index id]
  (let 
      [is-mouse-down (reagent/atom false)
       starting-mouse-x (reagent/atom nil)
       starting-mouse-y (reagent/atom nil)
       xstart-on-down (reagent/atom nil)
       ystart-on-down (reagent/atom nil)
       mouse-move-handler
       (fn [e]
         (let [x (.-clientX e)
               y (.-clientY e)]
           (swap! server-positions assoc id
                  {:x (- @xstart-on-down (* @main-window-zoom (- @starting-mouse-x x)))
                   :y (- @ystart-on-down (* @main-window-zoom (- @starting-mouse-y y)))})))]
    (fn [state static index id]
      (debug-render "server")
      (let [pos (server-position state id)
            server-state (get-in state [:server-state id])]
        [:g {:transform (translate (:x pos) (:y pos))
             :fill (server-color state id)
             :stroke (server-color state id)}
         [:text {:style {:pointer-events "none" :user-select "none"} :x 25 :y -20 :text-anchor "middle"} id]
         [:line {:x1 -35 :x2 -35 :y1 -40 :y2 40 :stroke-dasharray "5,5"}]
         [:image {:xlinkHref "images/server.png" :x 0 :y -10 :width 50 :height 100
                  :preserveAspectRatio "xMinYMin"
                  :on-context-menu
                  (when (not static)
                    (non-propagating-event-handler (fn [])))
                  :on-mouse-down
                  (when (not static)
                    (non-propagating-event-handler 
                     (fn [e]
                       (case (.-button e)
                         2 (reset! inspect {:x (:x pos) :y (:y pos)
                                            :value server-state
                                            :highlight-paths (map first
                                                                  (last (get-in state
                                                                                [:server-log id])))})
                         0 (reset! inspect {:x (:x pos) :y (:y pos)
                                            :value server-state
                                            :highlight-paths (map first
                                                                  (last (get-in state
                                                                                [:server-log id])))})))))}]
         [:line {:x1 -100 :x2 -50 :y1 0 :y2 0 :stroke-width 10}]
         [:g {:transform (translate -100 -40)}   ; inbox
          [timeouts-and-messages state id [(- (:x pos) 100) (- (:y pos) 40)] static]]
         [:circle {:fill "black" :stroke "black" :cx 25 :cy 30 :r 5
                   :on-mouse-down
                   (non-propagating-event-handler
                    (fn [e]
                      (reset! starting-mouse-x (.-clientX e))
                      (reset! starting-mouse-y (.-clientY e))
                      (reset! xstart-on-down (:x pos))
                      (reset! ystart-on-down (:y pos))
                      (.addEventListener js/document "mousemove" mouse-move-handler)
                      (.addEventListener js/document "mouseup"
                                         (fn [] (.removeEventListener js/document "mousemove"
                                                                      mouse-move-handler)))))}]]))))

(defn nw-state [state static]
  (component-map-indexed [:g {:style {:background "white"}}] server (:servers state) state static))

(defn history-move [path]
  (when-let [history-event (trees/get-path @event-history path)]
    (let [{new-state :state new-events :events new-message-extra-add-drop-data :message-extra-add-drop-data} (trees/root history-event)
          ch (chan)]
      (event-source/reset new-events ch)
      (go
        (let [new-events (<! ch)]
          (reset! message-extra-add-drop-data new-message-extra-add-drop-data)
          (reset! state new-state)
          (reset! events new-events)
          (reset! selected-event-path path))))))

(defn history-move-next []
  (let [new-path (trees/nth-child-path @selected-event-path 0)]
    (history-move new-path)))

(defn history-move-previous []
  (let [new-path (trees/parent-path @selected-event-path)]
    (history-move new-path)))

(defn history-view-event-line [path [x y] event parent-position]
  (debug-render "history-view-event-line")
  [:g {:fill "black" :stroke "black"}
   (when-let [[parent-x parent-y] parent-position]
     [:line {:x1 parent-x :x2 x :y1 parent-y :y2 y :stroke-width 5 :stroke-dasharray "5,1" :style {:z-index -5}}])])

(defn history-view-event [path [x y] event parent-position inspect selected]
  (debug-render "history-view-event")
  [:g {:fill "black" :stroke "black"}
   [:g {:transform (translate x y)}
    [:circle {:cx 0 :cy 0 :r 20
              :on-click (fn [] (log/log logger {:click "event" :path path}) (history-move path))
              :on-mouse-over #(reset! inspect [x y event])
              :on-mouse-out #(reset! inspect nil)
              :stroke (if selected "red" "black")
              :stroke-width 5
              :style {:z-index 5}}]]])

(defn history-event-inspector [inspect-event zoom xstart actual-width ystart]
  (when-let [[x y event] @inspect-event]
    (debug-render "history-event-inspector")
    (let [max-height 150
          max-width 200
          default-ratio (/ max-width max-height)
          actual-ratio @main-window-ratio
          height (if (> default-ratio actual-ratio) max-height (/ max-width actual-ratio))
          width (if (> default-ratio actual-ratio) (* max-height actual-ratio) max-width)]
      [:div {:style {:position "absolute"
                     :left (/ (+ (/ (- 750 750) 2) (-  x @xstart)) @zoom)
                     :bottom 110
                     :z-index 100}}
       [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
              :width width :height height :style {:border "1px solid black" :background-color "white"}
              :viewBox (gs/format "%d %d %d %d" @main-window-xstart @main-window-ystart (* 800 @main-window-zoom) (* 600 @main-window-zoom))
              :preserveAspectRatio "xMinYMin"}
        (nw-state (:state event) true)]
       ])))

(defn history-view-tree [inspect-event]
  [:g {:transform (translate 50 50)}
   (let [layout (trees/layout @event-history 75 50)]
     (doall
      (concat 
       (for [{:keys [position value path parent]} layout]
         ^{:key [path :line]} [history-view-event-line path position value parent])
       (for [{:keys [position value path parent]} layout]
         ^{:key [path]} [history-view-event path position value parent inspect-event (= path @selected-event-path)]))))])

(defn plus-not-below-1 [a b]
  (let [c (+ a b)]
    (if (>= c 1) c a)))

(defn history-view []
  (let [xstart (reagent/atom 0)
        ystart (reagent/atom 0)
        is-mouse-down (reagent/atom false)
        starting-mouse-x (reagent/atom nil)
        starting-mouse-y (reagent/atom nil)
        xstart-on-down (reagent/atom nil)
        ystart-on-down (reagent/atom nil)
        zoom (reagent/atom 1)
        inspect-event (reagent/atom nil)
        actual-width (reagent/atom 100)
        top (reagent/atom 0)
        left (reagent/atom 0)]
    (fn []
      (debug-render "history-view")
      [:div {:style {:position "relative"}}
       [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
              :preserveAspectRatio "xMinYMin"
              :ref (fn [elem] (when elem
                                (reset! top (.-top (.getBoundingClientRect elem)))
                                (reset! left (.-left (.getBoundingClientRect elem)))))
              :width "100%" :height 100
              :viewBox (gs/format "%d %d %d %d" @xstart @ystart (* 750 @zoom) (* 100 @zoom))
              :style {:border-left "1px solid black"
                      :border-right "1px solid black"
                      :border-bottom "1px solid black"}
              :on-mouse-down (fn [e]
                               (reset! is-mouse-down true)
                               (reset! starting-mouse-x (.-clientX e))
                               (reset! starting-mouse-y (.-clientY e))
                               (reset! xstart-on-down @xstart)
                               (reset! ystart-on-down @ystart)
                               true)
              :on-mouse-up (fn [] (reset! is-mouse-down false))
              :on-mouse-move (fn [e]
                               (when @is-mouse-down
                                 (let [x (.-clientX e)
                                       y (.-clientY e)]
                                   (reset! xstart (+ @xstart-on-down (* @zoom (- @starting-mouse-x x))))
                                   (reset! ystart (+ @ystart-on-down (* @zoom (- @starting-mouse-y y)))))))
              :on-wheel (non-propagating-event-handler
                         (fn [e]
                           (let [change (delta-from-wheel @zoom e)]
                             (cond
                              (>= (+ @zoom change) 1)
                              (do
                                (swap! xstart - (* change (- (.-clientX e) @left)))
                                (swap! ystart - (* change (- (.-clientY e) @top)))
                                (swap! zoom + change))
                              (not (= @zoom 1))
                              (do
                                (swap! xstart - (* (- 1 @zoom)
                                                   (- (.-clientX e) @left)))
                                (swap! ystart - (* (- 1 @zoom)
                                                   (- (.-clientY e) @top)))
                                (reset! zoom 1))
                              ))))}
        [history-view-tree inspect-event]]
       [history-event-inspector inspect-event zoom xstart actual-width ystart]])))


(defn prefixes [path]
  (for [i (range 1 (inc (count path)))]
    (vec (take i (map str path)))))

(defn path-prefix-set [path-set]
  (into
   #{}
   (concat
    (mapcat prefixes path-set)
    (map #(conj (vec %) ::star) path-set))))

(defn inspector []
  (when-let [{:keys [x y value highlight-paths actions]} @inspect]
    (debug-render "inspector")
    (log/log logger {:inspect value})
    [:div {:style {:position "absolute" :top (/ (- y @main-window-ystart) @main-window-zoom) :left (/ (- x @main-window-xstart) @main-window-zoom)
                   :border "1px solid black" :background "white"
                   :padding "10px"}}
     [:> json-tree {:hideRoot true
                    :theme
                    (let [prefix-set (path-prefix-set highlight-paths)
                          theme-fn
                          (fn [path]
                            (let [path (vec (reverse path))]
                              (when
                                  (some #(contains? prefix-set (conj % ::star))
                                        (prefixes path))
                                {"text-decoration" "underline"}))
                            )]
                      (clj->js
                       {:label (fn [styling path]
                                 (let [style (get (js->clj styling) "style")
                                       path (js->clj path)]
                                   (clj->js {:style (merge style (theme-fn path))})))
                        :valueLabel (fn [styling type path]
                                      (let [style (get (js->clj styling) "style")
                                            path (js->clj path)]
                                        (clj->js {:style (merge style (theme-fn path))})))
                        :extend {:base00 "#000000"
                                 :base01 "#303030"
                                 :base02 "#505050"
                                 :base03 "#b0b0b0"
                                 :base04 "#d0d0d0"
                                 :base05 "#e0e0e0"
                                 :base06 "#f5f5f5"
                                 :base07 "#ffffff"
                                 :base08 "#fb0120"
                                 :base09 "#fc6d24"
                                 :base0A "#fda331"
                                 :base0B "#a1c659"
                                 :base0C "#76c7b7"
                                 :base0D "#6fb3d2"
                                 :base0E "#d381c3"
                                 :base0F "#be643c"}}))
                    :data (clj->js value)}]
     [:br]
     (when actions
       [b/ButtonGroup
        (doall (for [[name action] actions]
                 ^{:key name} [b/Button {:color "secondary"
                                         :on-click (fn []
                                                 (reset! inspect nil) (do-next-event action))} name]))]
       )]))

(defn add-trace [trace-db name trace]
  (let [{:keys [trace servers]} trace]
    (conj trace-db {:name name :trace trace :servers servers :id (count trace-db)})))

(defonce traces-local (local-storage (atom []) :traces))

(defn trace-upload-modal [close]
  (reagent/with-let [file (reagent/atom nil)
                     file-loading (reagent/atom false)
                     deserialize-error (reagent/atom nil)
                     trace-name (reagent/atom nil)]
    (debug-render "trace-upload")
    [:span
     [b/ModalHeader {:toggle close} "Upload trace"]
     [b/ModalBody
      [b/Form
       [b/FormGroup
        (when @file-loading
          [:div {:class "spinner-border spinner-border-sm"}
           [:span {:class "sr-only"} "Loading..."]])
        [b/Input {:type "file"
                  :on-change
                  (fn [t]
                    (let [f (-> t (.-target) (.-files) (.item 0))
                          reader (js/FileReader.)
                          on-load (fn [e] (reset! file (-> e (.-target) (.-result))))]
                      (reset! file-loading true)
                      (set! (.-onload reader) on-load)
                      (.readAsText reader f)
                      (reset! file-loading false)))}]]
       [b/Input {:placeholder "Trace name" :value @trace-name
                 :on-change (fn [e]
                            (reset! trace-name (-> e .-target .-value)))}]]
      [b/Button {:color "primary" :disabled (not @file)
                 :on-click
                 (fn []
                   (swap! traces-local add-trace (.-value (get-element "trace-name"))
                          (js->clj (.parse js/JSON @file) :keywordize-keys true))
                   (close))}
       "Upload"]]]))

(defn trace-upload-modal-show [] (modals/show-modal trace-upload-modal))

(defn make-trace [trace servers]
  (into [{:reset {:servers servers :messages {} :server-state {}}}] trace))


(defn download [filename content & [mime-type]]
  (let [mime-type (or mime-type (str "text/plain;charset=" (.-characterSet js/document)))
        blob (new js/Blob
                  (clj->js [content])
                  (clj->js {:type mime-type}))]
    (js/saveAs blob filename)))

(defn download-trace [trace servers]
  (let [json (.stringify js/JSON (clj->js {:trace trace :servers servers}))]
    (download "trace.json" json "application/json")))

(defn switch-to-trace [trace servers]
  (reset! event-history nil)
  (reset! selected-event-path [])
  (let [tr (make-trace trace servers)]
    (reset! events (event-source/StaticEventSource. tr))
    (do-next-event nil)))



(defn trace-display []
  (let [traces (reagent/atom @traces-local)
        fetch-traces (reset! traces @traces-local)
        expanded (reagent/atom false)
        file-channel (chan)]
    (add-watch traces-local :copy-to-traces (fn [_ _ _ v] (reset! traces v)))
    (fn []
      (debug-render "trace-display")
      [b/UncontrolledDropdown {:nav true :navbar true}
       [b/DropdownToggle {:caret true :nav true :navbar true} "Traces"]
       [b/DropdownMenu
        [b/DropdownItem {:on-click trace-upload-modal-show} "Upload"]
        (doall
         (for [{:keys [name trace id servers]} @traces]
           ^{:key name}
           [b/DropdownItem
            [:a {:href "#" :on-click #(switch-to-trace trace servers)} name]
            [:span " "]
            [:a {:href "#" :on-click #(download-trace trace servers)} "(download)"]
            [:span " "]
            [:a {:href "#" :on-click
                 (fn [] (swap! traces
                               #(vec (remove-one (partial fields-match
                                                          [:id] {:id id})
                                                 %))))} "(delete)"]]))]])))





(defn main-window []
  (reagent/with-let
    [top (reagent/atom 0)
     left (reagent/atom 0)
     dragging (reagent/atom false)
     startx (reagent/atom 0)
     xstart-on-down (reagent/atom 0)
     ystart-on-down (reagent/atom 0)
     starty (reagent/atom 0)
     this (reagent/current-component)
     resize-handler (fn []
                      (let [rect (-> this
                                     (reagent/dom-node)
                                     (.getBoundingClientRect))]
                        (reset! main-window-ratio (/ (.-width rect) (.-height rect)))))
     _ (.addEventListener js/window "resize" resize-handler)]
    [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
           :width "100%" :height 600 :style {:border "1px solid black"}
           :viewBox (gs/format "%d %d %d %d" @main-window-xstart @main-window-ystart (* 800 @main-window-zoom) (* 600 @main-window-zoom))
           :ref (fn [elem] (when elem
                             (let [rect (.getBoundingClientRect elem)]
                               (reset! main-window-ratio (/ (.-width rect) (.-height rect)))
                               (reset! top (.-top rect))
                               (reset! left (.-left rect)))))
           :preserveAspectRatio "xMinYMin"
           :on-mouse-down (fn [e]
                            (reset! dragging true)
                            (reset! startx (.-clientX e))
                            (reset! starty (.-clientY e))
                            (reset! xstart-on-down @main-window-xstart)
                            (reset! ystart-on-down @main-window-ystart)
                            (reset! inspect nil))
           :on-mouse-up (fn [] (reset! dragging false))
           :on-mouse-move
           (fn [e]
             (when @dragging
               (reset! main-window-xstart
                       (+ @xstart-on-down
                          (* @main-window-zoom (- @startx (.-clientX e)))))
               (reset! main-window-ystart
                       (+ (* @main-window-zoom (- @starty (.-clientY e)))
                          @ystart-on-down))))
           :on-wheel (non-propagating-event-handler
                      (fn [e]
                        (let [change (delta-from-wheel @main-window-zoom e)]
                          (cond
                            (>= (+ @main-window-zoom change) 1)
                            (do
                              (swap! main-window-xstart - (* change (- (.-clientX e) @left)))
                              (swap! main-window-ystart - (* change (- (.-clientY e) @top)))
                              (swap! main-window-zoom + change))
                            (not (= @main-window-zoom 1))
                            (do
                              (swap! main-window-xstart - (* (- 1 @main-window-zoom)
                                                             (- (.-clientX e) @left)))
                              (swap! main-window-ystart - (* (- 1 @main-window-zoom)
                                                             (- (.-clientY e) @top)))
                              
                              (reset! main-window-zoom 1))
                            ))))}
     (nw-state @state false)]
    (finally (.removeEventListener js/window "resize" resize-handler))))

(defn toggler [a]
  (fn [] (swap! a not)))


(defparser predicate-parser
  "predicate = <whitespace> name path <whitespace> op <whitespace> value <whitespace>;
   name = identifier
   path = segments
   <segments> = <'.'> identifier | segments segments
   op = '=';
   value = string | number | bool;
   <string> = <'\"'> #'[^\"]*' <'\"'>;
   <identifier> = #'[A-Za-z0-9]+';
   number = #'[0-9]+';
   bool = 'false'|'true'
   whitespace = #'\\s*'")

(def predicate-transform
  {:number js/parseInt
   :bool #(boolean (#{"true"} %))})

(defn parse-predicate [text]
  (let [tree (insta/parse predicate-parser text)]
    (when-not (insta/failure? tree)
      (let [tree (insta/transform predicate-transform tree)]
        (match tree
               [:predicate
                [:name node]
                [:path & path]
                _
                [:value value]]
               {:type :node-state :node node :path path :value value})))))

(defn run-until-predicate-modal [close]
  (log "predicate modal")
  (reagent/with-let [predicate (reagent/atom "")]
    [:span
     [b/ModalHeader "Run until predicate matches"]
     [b/ModalBody
      [b/Form
       ;; I'd like this to be a b/Input instead of a textarea, but that
       ;; will break rendering: https://github.com/reagent-project/reagent/issues/79
       [:textarea {:class "form-control"
                   :on-change (fn [e]
                                (reset! predicate (-> e .-target .-value)))
                   :value @predicate}]]]
     [b/ModalFooter
      (let [parsed-pred (parse-predicate @predicate)]
        [b/Button {:color "primary" :disabled (or (not parsed-pred)
                                                  (not (some #{(:node parsed-pred)} (:servers @state))))
                   :on-click (fn []
                               (do-next-event {:type :run-until :pred parsed-pred})
                               (close))}
         "Run"])
      [b/Button {:on-click close :color "secondary"} "Cancel"]]]))

(defn run-until-predicate-modal-show []
  (modals/show-modal run-until-predicate-modal))

(defn run-until-controls []
  (let [enabled (event-source/supports? @events :run-until)]
    [b/UncontrolledDropdown {:nav true :navbar true
                             :disabled (not enabled)}
     [b/DropdownToggle {:caret true :nav true :navbar true :disabled (not enabled)
                        :class (when-not enabled "disabled")}
      "Run until"]
     [b/DropdownMenu {:disabled true}
      [b/DropdownItem {:on-click run-until-predicate-modal-show} "Predicate..."]]]))

(defonce debug-display-state (reagent/atom nil))
(defonce debugger (make-debugger debug-display-state))

(defn debugger-status-display [{:keys [status servers]}]
  [:div {:style {:display "inline"}}
   "Debugger "
   (if (or (not status) (= status :processing) (= (count servers) 0))
     [:div {:class "spinner-border spinner-border-sm"}
      [:span {:class "sr-only"} "Loading..."]])])

;; (defn log-status []
;;   (when (:connected @log-state)
;;     [:div {:style {:position "absolute" :top 5 :left 5 :border "1px solid black"}}
;;      (if (:userid @log-state)
;;        [:a {:href "#" :on-click #(go (>! logger {:type :unregister}))} "Disable logging"]
;;        [:div
;;         [:span "User ID: "]
;;         [:input#userid {:type "text"}]
;;         [:button {:on-click (fn []
;;                               (let [userid (.-value (get-element "userid"))]
;;                                 (go (>! logger {:type :register :userid userid}))))}
;;          "Enable logging"]]
;;        )]))


(defn log-display []
  [b/UncontrolledDropdown {:nav true :navbar true}
   [b/DropdownToggle {:caret true :nav true :navbar true} "Logging"]
   [b/DropdownMenu
    (if (:userid @log-state)
      [b/DropdownItem {:on-click #(go (>! logger {:type :unregister}))} "Unregister"]
      [:div 
       [b/Form {:class "px-4"}
        [b/Input {:type "text" :placeholder "username" :id "logger-userid"}]]
       [b/DropdownItem {:on-click (fn []
                                    (let [userid (.-value (get-element "logger-userid"))]
                                      (go (>! logger {:type :register :userid userid}))))}
        "Register"]])]])

(defn debug-display []
  (let [st @debug-display-state]
    (debug-render "debug display")
    [b/Nav {:navbar true :class "ml-auto"}
     [b/UncontrolledDropdown {:nav true :navbar true}
      [b/DropdownToggle {:caret false :nav true :navbar true}
       [debugger-status-display st]]
      [b/DropdownMenu {:right true}
       [b/DropdownItem {:header true}
        (str "Connections: " (clojure.string/join "," (:servers st)))]
       [b/DropdownItem {:disabled (or (:started st) (empty? (:servers st)))
                        :on-click (fn []
                                    (reset! events debugger)
                                    (do-next-event {:type :start}))}
        "Debug system"]
       [b/DropdownItem {:disabled (or (:started st)
                                      (empty? (:servers st))
                                      (not (:trace st)))
                        :on-click (fn []
                                    (reset! events debugger)
                                    (do-next-event {:type :trace :trace (:trace st)}))}
        "Debug system with trace"]]]]))


(defn navbar []
  (reagent/with-let
    [collapsed (reagent/atom true)]
    [b/Navbar {:color "light" :light true :expand "md"}
     [b/NavbarBrand [:img {:src "images/oddity.png" :height 30}]]
     [b/NavbarToggler {:on-click (toggler collapsed)}]
     [b/Collapse {:isOpen (not @collapsed) :navbar true}
      [b/Nav {:navbar true :class "mr-auto"}
       [b/UncontrolledDropdown {:nav true :navbar true}
        [b/DropdownToggle {:caret true :nav true :navbar true} "Display"]
        [b/DropdownMenu
         [b/DropdownItem {:on-click
                          (fn []
                            (reset-server-positions @state))}
          "Reset server positions"]]]
       (when (and (get-config :enable-logging) (:connected @log-state))
         [log-display])
       (when (get-config :enable-traces)
        [trace-display])]
      [b/Nav {:navbar true :class "mx-auto"}
       [b/NavItem
        [b/NavLink {:href "#"
                    :on-click
                    (fn [] (do-next-event nil))
                    :disabled (not @events)}
         "Next event"]]
       [run-until-controls]]
      (when (get-config :enable-debugger)
        [debug-display])
      ]]))

(defn home-page []
  (fn []
    (debug-render "home page")
    [:div {:style {:position "relative"}}
     [modals/modal]
     [navbar]
     [:div {:style {:padding "10px"}}
      [main-window]
      [:br]
      [history-view]
      [inspector]]]))

;; -------------------------
;; Routes

(def page (reagent/atom #'home-page))

(defn current-page []
  (debug-render "page")
  [:div [@page]])

(secretary/defroute "/" []
  (reset! page #'home-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn keypress-handler [evt]
  (when (not (modals/modal-showing))
    (cond
      (= (.-keyCode evt) 110) (history-move-next)
      (= (.-keyCode evt) 112) (history-move-previous))))

(defn bind-keys []
  (.addEventListener js/document "keypress" keypress-handler))

(defn init! []
  (bind-keys)
  (next-event-loop)
  (accountant/configure-navigation!
   {:nav-handler
    (fn [path]
      (secretary/dispatch! path))
    :path-exists?
    (fn [path]
      (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
