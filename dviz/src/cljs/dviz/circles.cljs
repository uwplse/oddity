(ns dviz.circles)

;; Circles

(defn circle [cx cy r]
  {:cx cx :cy cy :r r})

(defn radian [c r]
  {:x (+ (:cx c) (* (:r c) (Math/cos r)))
   :y (+ (:cy c) (* (:r c) (Math/sin r)))})

(defn angle [c a]
  (radian c (* (/ a 180) Math/PI)))
