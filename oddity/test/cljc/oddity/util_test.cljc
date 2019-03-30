(ns oddity.util_test
  (:require #?(:clj  [clojure.test :as ct]
               :cljs [cljs.test :as ct :include-macros true])
            [oddity.util :as util]))

(ct/deftest coerce-keys-test
  (ct/is (= (util/coerce-keys {} []) {}))
  (ct/is (= (util/coerce-keys {:a 1 "b" 2} [:b]) {:b 2}))
  (ct/is (= (util/coerce-keys {:a 1 "b" 2} [:a :b]) {:a 1 :b 2}))
  (ct/is (= (util/coerce-keys {:a 1 "b" 2} [:b] {:c :a}) {:b 2 :c 1}))
  (ct/is (= (util/coerce-keys {:a 1 "b" 2 :d 3} [:b] {:c :a}) {:b 2 :c 1})))
