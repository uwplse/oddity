(ns oddity.dsmodelchecker
  (:require
   [oddity.modelchecker :refer [IState dfs restart!]]
   [oddity.util :refer [remove-one]]
   [oddity.coerce :as c]
   [clojure.walk :refer [keywordize-keys]]))

(defprotocol ISystemControl
  (send-message! [this message] "Send a message. Returns the result.")
  (restart-system! [this] "Restart the system. Returns a list of results."))

(defn apply-state-update [state update]
  (let [{path :path value :value} update]
    (assoc-in state path value)))

(defn apply-state-change [{:keys [timeouts messages states]} body delta]
  (let [node-id (:to body)
        messages (if (= (:msgtype body) "msg")
                   (vec (remove-one #(= % body) messages))
                   messages)
        {:keys [cleared-timeouts set-timeouts send-messages state-updates]} delta
        cleared-timeouts (map #(assoc % :to node-id) cleared-timeouts)
        set-timeouts (map #(assoc % :to node-id) set-timeouts)
        send-messages (map #(assoc % :from node-id) send-messages)
        state (get states node-id)]
    {:timeouts (into (vec (remove #(some #{%} cleared-timeouts) timeouts)) set-timeouts)
     :messages (into messages send-messages)
     :states (assoc states node-id
                    (reduce apply-state-update state state-updates))}))

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

(defn sort-actions [pred actions]
  (let [node (case (:type pred) :node-state (:node pred))]
    (sort (fn [a1 a2]
            (cond
              (and (message? a1) (timeout? a2)) true
              (and (message? a2) (timeout? a1)) false
              (to? node a1) true
              (to? node a2) false
              (from? node a1) true
              (from? node a2) false
              :default true))
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
    (let [init-state (new-state (c/coerce-responses (restart-system! sys)))
          prefix-state (reduce
                        (fn [st m]
                          (apply-state-change st m (c/coerce-response (send-message! sys m))))
                               init-state (map c/coerce-message prefix))]
      (->DSState sys prefix prefix-state)))
  (actions [this pred]
    (let [timeout-actions (map (fn [t] {:deliver-timeout (assoc t :msgtype "timeout")})
                               (:timeouts state))
          message-actions (map (fn [m] {:deliver-message (assoc m :msgtype "msg")})
                               (:messages state))]
      (sort-actions pred (concat message-actions timeout-actions))))
  (run-action! [this action]
    (let [action (or (:deliver-timeout action) (:deliver-message action))
          response (c/coerce-response (send-message! sys action))
          state (apply-state-change state action response)]
      (->DSState sys prefix state)))
  (matches? [this pred]
    (state-matches? pred state)))

(defn make-dsstate [sys prefix]
  (restart! (->DSState sys prefix nil)))
