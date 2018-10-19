(ns oddity.macros)

(defmacro write-and-read-result [to-chan val from-chan]
  `(do
     (cljs.core.async/>! ~to-chan ~val)
     (cljs.core.async/<! ~from-chan)))
