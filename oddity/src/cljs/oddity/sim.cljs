(ns oddity.sim
  (:require [oddity.event-source :refer [IEventSource]]
            [oddity.util :refer [remove-one]]
            [goog.string :as gs]
            [goog.string.format]
            [cljs.core.async :refer [put! take! chan <! >! timeout close!]]))

;; handlers:
(comment
  {:return-type [[(path val)] [(to type body)]]}
  {:net-handler (fn [to-server-id from-server-id type body server-state]
                  -> return-type)
   :extern [(fn [server-id server-state] -> [val],
             fn [server-id val server-state] -> return-type)]}
  )

;; state:
(comment
  {:servers [id]
   :server-state {}
   :messages [{:from :to :type :body}]})

(defn possible-actions [handlers state]
  (concat
   (for [m (:messages state)]
     [:deliver m])
   (let [[precond _] (:extern handlers)]
     (for [server-id (:servers state)
           extern-name (precond server-id (get-in state [:server-state server-id]))]
       [:extern {:to server-id :name extern-name}]))))

(defn tag-messages [server-id messages]
  (for [m messages] (assoc m :from server-id)))

(defn event [servers server-id state-updates new-messages]
  {:reset {:servers servers}
   :send-messages (tag-messages server-id new-messages)
   :update-state (concat [server-id] state-updates)})

(defn update-state [state server-id state-updates new-messages]
  (as-> state state
    (reduce (fn [state [path val]]
              (assoc-in state (concat [:server-state server-id] path) val))
            state state-updates)
    (update-in state [:messages] #(concat % (tag-messages server-id new-messages)))))

(defn step-deliver [handlers state message]
  (let [state (update-in state [:messages] (partial remove-one #(= % message)))
        {:keys [from to type body]} message
        server-state (get-in state [:server-state to])
        [state-updates new-messages] ((:net-handler handlers) to from type body server-state)]
    [(into (event (:servers state) to state-updates new-messages) {:deliver-message message})
     (update-state state to state-updates new-messages)]))

(defn step-extern [handlers state extern]
  (let [{:keys [to name]} extern
        server-state (get-in state [:server-state to])
        [state-updates new-messages] ((second (:extern handlers)) to name server-state)]
    [(event (:servers state) to state-updates new-messages)
     (update-state state to state-updates new-messages)]))

(defn step [handlers state]
  (let [actions (possible-actions handlers state)
        [action-type action] (rand-nth actions)]
    (case action-type
      :deliver (step-deliver handlers state action)
      :extern (step-extern handlers state action))))

(defrecord Simulation [handlers st]
  IEventSource
  (next-event [this ch _]
    (when-let [[e st'] (step handlers st)]
      (put! ch [e (Simulation. handlers st')])))
  (reset [this ch] (put! ch this)))
