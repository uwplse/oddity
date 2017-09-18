(ns dviz.trees)

(defn leaf [v]
  (list v))

(defn root [tree]
  (first tree))

(defn children [tree]
  (rest tree))

(defn update-nth [l n f & args]
  (concat (take n l) (list (apply f (nth l n) args)) (drop (inc n) l)))

(defn tree-append [tree zipper v]
  (if (empty? zipper)
    (cons (root tree) (concat (children tree) (list (leaf v))))
    (cons (root tree) (update-nth (children tree) (first zipper)
                                  tree-append (rest zipper) v))))

(defn tree-get [tree zipper]
  (if (empty? zipper)
    (root tree)
    (recur (nth (children tree) (first zipper)) (rest zipper))))

(defn tree-layout
  "Takes a tree and layes it out, returning a list of maps:
  {:position [<x> <y>] :value <v> :path <zipper>}"
  ([tree dx dy] (tree-layout tree dx dy 0 0 [] nil))
  ([tree dx dy x y path parent]
   (let [root-layout {:position [x y] :value (root tree) :path path :parent parent}]
     (cons root-layout
           (apply concat
                  (for [index (range (count (children tree)))
                        :let
                        [child (nth (children tree) index)
                         new-x (+ x dx)
                         new-y (+ y (* index dy))
                         new-path (conj path index)]]
                    (tree-layout child dx dy new-x new-y new-path [x y])))))))
