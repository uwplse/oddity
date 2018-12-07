(ns oddity.util
  (:require [clojure.data :as data]))

(defn foo-cljc [x]
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(defn remove-one [pred coll]
  (when-let [x (first coll)]
    (if (pred x)
      (rest coll)
      (cons x (remove-one pred (rest coll))))))

(defn paths
  ([m] (paths m []))
  ([m path]
   (cond
     (or (map? m))
     (into [] (mapcat (fn [[k v]] (paths v (conj path k))) m))

     (and (sequential? m) (some nil? m))
     (into [] (apply concat
                     (map-indexed (fn [k v] (when (some? v) (paths v (conj path k)))) m)))
     
     :else
     [[path m]])))

(defn correct-truncated-seqs [in-1 in-2 in-both]
  (cond
    (and (sequential? in-1) (nil? in-2))
    in-both
    (and (map? in-1) (or (map? in-2) (nil? in-2)))
    (let [all-keys  (set (concat (keys in-1) (keys in-2)))]
      (into {} (map (fn [k] [k (correct-truncated-seqs (get in-1 k) (get in-2 k) (get in-both k))])
                    all-keys)))
    :else in-2))

(defn differing-paths [m1 m2]
  (let [[in-1 in-2 in-both] (data/diff m1 m2)
        diffs (correct-truncated-seqs in-1 in-2 in-both)]
    (if diffs
      (paths diffs)
      [])))

(defn fields-match [fields m1 m2]
  (every? #(= (get m1 %) (get m2 %)) fields))

(defn coerce-keys [m ks]
  (into {} (map (fn [k] [k (or (get m k) (get m (name k)))]) ks)))

