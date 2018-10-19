(ns oddity.paxos
  (:require [oddity.util :refer [remove-one]]
            [goog.string :as gs]
            [goog.string.format]
            [cljs.spec.alpha :as s]
            [oddity.sim :refer [Simulation]]))


(s/def ::id (s/and int? #(<= 0 %)))

(s/def ::to ::id)
(s/def ::from ::id)
(s/def ::type #{:p1a :p1b})
(s/def ::body map?)
(s/def ::state map?)

;(s/def ::balnum (s/and int? #(<= 0 %)))
;(s/def ::bal (s/keys :req-un [::id ::balnum]))

(s/def ::update (s/cat :path (s/coll-of keyword?) :val any?))
(s/def ::message (s/keys :req-un [::to ::type ::body]))

(s/def ::handler-ret (s/cat :updates (s/coll-of ::update) :messages (s/coll-of ::message)))

(defn bal-le [bal1 bal2]
  (if (nil? bal1) true
      (if (nil? bal2) false
          (let [{id1 :id num1 :num} bal1
                {id2 :id num2 :num} bal2]
            (cond
              (< num1 num2) true
              (and (= num1 num2) (<= id1 id2)) true
              :else false)))))

(defn bal-next [bal id]
  (if (nil? bal) {:num 1 :id id}
      {:num (inc (:num bal)) :id id}))

(defn extern-precond [id state]
  (if (get-in state [:leader :active])
    []
    [:try]))

(defn extern-handler [id _ state]
  (let [proposals #{}
        votes #{}
        state-updates [[[:leader :proposals] proposals]
                       [[:leader :votes] votes]]
        bal (get-in state [:leader :bal])
        messages (for [to (:servers state)]
                   {:to to :type :p1a :body {:bal bal}})]
    [state-updates messages]))

(def extern
  [extern-precond extern-handler])

(defn net-handler [to from type body state]
  (case type
    :p1a (let [{bal :bal} body
               my-bal (get-in state [:acceptor :bal])]
           (if (bal-le bal my-bal)
             [nil [{:to from :type :p1b :body {:ok false :bal bal :higher-bal my-bal}}]]
             [[[[:acceptor :bal] bal]]
              [{:to from :type :p1b
                :body {:ok true :bal bal
                       :proposals (get-in state [:acceptor :proposals])}}]]))
    :p1b (let [{:keys [:bal :ok]} body]
           (if (= bal (get-in state [:leader :bal]))
             (if ok
               (let [votes (set (conj (get-in state [:leader :votes]) from))
                     state-updates [[[:leader :votes] votes]
                                    [[:leader :proposals]
                                     (into (get-in state [:leader :proposals])
                                           (:proposals body))]]]
                 (if (> (count votes) (/ (count (:servers state)) 2))
                   (let [state-updates
                         (into state-updates
                               [[[:leader :active] true]])]
                     [state-updates nil])
                   [state-updates nil]))
               (let [state-updates [[[:leader :bal] (bal-next (:higher-bal body) to)]]]
                 [state-updates nil]))
             [nil nil]))))

(defn paxos-sim [n]
  (let [servers (range n)]
    (Simulation. {:extern extern :net-handler net-handler}
                 {:servers servers
                  :messages []
                  :server-state (into {} (for [id servers] [id {:servers servers}]))})))

(s/fdef net-handler
        :args (s/cat :to ::to :from ::from :type ::type :body ::body :state map?)
        :ret ::handler-ret)
