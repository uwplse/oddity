(ns oddity.dsmodelchecker
  (:require
   [oddity.modelchecker :refer [IState dfs restart!]]
   [oddity.util :refer [remove-one]]
   [oddity.coerce :as c]
   [clojure.walk :refer [keywordize-keys]]
   [taoensso.tufte :as tufte :refer [p]]))

(defprotocol ISystemControl
  (send-message! [this message] "Send a message. Returns the result.")
  (restart-system! [this] "Restart the system. Returns a list of results."))

(defn apply-state-update [state update]
  (let [{path :path value :value} update]
    (assoc-in state path value)))

(defn apply-state-change [{current-states :states :keys [timeouts messages]} body delta]
  (let [node-id (:to body)
        messages (if (= (:msgtype body) "msg")
                   (vec (remove-one #(= % body) messages))
                   messages)
        {:keys [cleared-timeouts set-timeouts send-messages state-updates state-id states]} delta
        cleared-timeouts (filter #(= (:to %) node-id) cleared-timeouts)
        set-timeouts (filter #(= (:to %) node-id) set-timeouts)
        send-messages (filter #(= (:from %) node-id) send-messages)
        state (get current-states node-id)]
    {:timeouts (into (vec (remove #(some #{%} cleared-timeouts) timeouts)) set-timeouts)
     :messages (into messages send-messages)
     :states (if states
               (assoc current-states node-id (get states node-id))
               (assoc current-states node-id
                      (reduce apply-state-update state state-updates)))
     :state-id state-id}))

(defn new-state [restart-response]
  (let [deltas (:responses restart-response)]
    (reduce (fn [st [id delta]] (apply-state-change st {:msgtype "start" :to id} delta))
            {:timeouts [] :messages [] :states {}} deltas)))

(defn message? [action]
  (= (:msgtype action) "msg"))

(defn timeout? [action]
  (= (:msgtype action) "timeout"))

(defn to? [h action]
  (= (:to action) h))

(defn from? [h action]
  (= (:from action) h))

(defn action-priority [action node]
  (cond
    (and (message? action) (to? node action)) 0
    (and (message? action) (from? node action)) 1
    (to? node action) 2
    (message? action) 3
    :default 4))

(defn sort-actions [pred actions]
  (let [node (case (:type pred) :node-state (:node pred))]
    (sort (fn [a1 a2]
            (let [a1 (or (:deliver-timeout a1) (:deliver-message a1))
                  a2 (or (:deliver-timeout a2) (:deliver-message a2))]
              (<= (action-priority a1 node) (action-priority a2 node))))
          actions)))

(defn state-matches? [pred state]
  (case (:type pred)
    :node-state
    (let [node-id (:node pred)
          node-state (get-in state [:states node-id])]
      (= (get-in node-state (:path pred)) (:value pred)))))

(defrecord DSState [sys prefix state]
  IState
  (restart! [this]
    (p :restart! 
       (let [init-state (new-state (c/coerce-responses
                                    (p :restart-system! (restart-system! sys))))
             prefix-state (reduce
                           (fn [st m]
                             (apply-state-change st m (c/coerce-response
                                                       (p :restart-send-message!
                                                          (send-message! sys m)))))
                           init-state (map c/coerce-message-or-timeout prefix))]
         (->DSState sys prefix prefix-state))))
  (actions [this pred]
    (p :actions 
       (let [timeout-actions (map (fn [t] {:deliver-timeout t})
                                  (:timeouts state))
             message-actions (map (fn [m] {:deliver-message m})
                                  (:messages state))
             actions (sort-actions pred (concat message-actions timeout-actions))]
         actions)))
  (run-action! [this action]
    (p :run-action 
       (let [action (or (:deliver-timeout action) (:deliver-message action))
             response (c/coerce-response (send-message! sys (assoc action :state-id (:state-id state))))
             state (apply-state-change state action response)]
         (->DSState sys prefix state))))
  (matches? [this pred]
    (p :matches? 
       (state-matches? pred state))))

(defn make-dsstate [sys prefix]
  (restart! (->DSState sys prefix nil)))
