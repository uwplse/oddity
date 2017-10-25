(ns dviz.util)

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
   (if (map? m)
     (into [] (mapcat (fn [[k v]] (paths v (conj path k))) m))
     [[path m]])))
