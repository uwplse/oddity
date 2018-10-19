(ns dviz.trees)

(defn leaf [v]
  (list v))

(defn root [tree]
  (first tree))

(defn children [tree]
  (rest tree))

(defn update-nth [l n f & args]
  (concat (take n l) (list (apply f (nth l n) args)) (drop (inc n) l)))

(defn set-nth [l n x]
  (concat (take n l) (list x) (drop (inc n) l)))

(defn append-path
  "Takes a tree, a path and a value. Returns a new tree with that
  value appended at the position identified by the path and a new
  path that identifies the appended value's position"
  [tree path v]
  (if (empty? path)
    [(cons (root tree) (concat (children tree) (list (leaf v))))
     [(count (children tree))]]
    (let [n (first path)
          path' (rest path)
          subtree (nth (children tree) n)
          [subtree' path'] (append-path subtree path' v)]
      [(cons (root tree) (set-nth (children tree) n subtree'))
       (cons n path')])))

(defn parent-path [path]
  (take (dec (count path)) path))

(defn nth-child-path [path n]
  (concat path [n]))

(defn get-path [tree path]
  (if (empty? path)
    tree
    (let [child-index (first path)
          my-children (children tree)]
      (if (>= child-index (count my-children))
        nil
        (recur (nth (children tree) (first path)) (rest path))))))

(defn get-whole-path
  ([tree path] (get-whole-path tree path []))
  ([tree path acc]
   (if (empty? path)
     (conj acc (root tree))
     (let [n (first path)
           path' (rest path)
           tree' (nth (children tree) n)
           acc' (conj acc (root tree))]
       (recur tree' path' acc')))))

(defn layout
  "Takes a tree and layes it out, returning a list of maps:
  {:position [<x> <y>] :value <v> :path <path>}"
  ([tree dx dy] (layout tree dx dy 0 0 [] nil))
  ([tree dx dy x y path parent]
   (if tree
     (let [root-layout {:position [x y] :value (root tree) :path path :parent parent}]
       (cons root-layout
             (apply concat
                    (loop [index 0
                           child-layouts []
                           next-y y]
                      (if (>= index (count (children tree)))
                        child-layouts
                        (let [child (nth (children tree) index)
                              new-x (+ x dx)
                              new-y next-y
                              new-path (conj path index)
                              child-layout (layout child dx dy new-x new-y new-path [x y])
                              max-y (apply min (for [{[descendant-x descendant-y] :position} child-layout]
                                                 descendant-y))]
                          (recur (inc index) (conj child-layouts child-layout) (- max-y dy)))))))))))
