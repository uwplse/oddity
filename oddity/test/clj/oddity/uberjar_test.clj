(ns oddity.uberjar-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :refer [sh]]
            [etaoin.api :refer :all]))

(defn compile-uberjar []
  (sh "lein" "uberjar"))

(def uberjar-compiled (atom false))
(def uberjar (atom nil))

(defn start-uberjar []
  (reset! uberjar (.start (ProcessBuilder. (into-array ["java" "-jar" "target/oddity.jar"]))))
  (Thread/sleep 10000))

(defn stop-uberjar []
  (.destroy @uberjar))

(defn uberjar-fixture [f]
  (when-not @uberjar-compiled
    (compile-uberjar)
    (reset! uberjar-compiled true))
  (start-uberjar)
  (f)
  (stop-uberjar))

(def ^:dynamic *driver*)

(defn driver-fixture 
  "Browser test instance. Binds to *driver* for use in tests."
  [f]
  (with-chrome-headless {:args ["--no-sandbox"]} driver
    (binding [*driver* driver]
      (f))))

;; uberjar-fixture should really be :once, but clojure.test will execute :once
;; fixtures even if it isn't running the corresponding tests. We only want to
;; run this during integration tests, so we have a hack with :each and an atom.
(use-fixtures :each uberjar-fixture driver-fixture)

(deftest ^:integration basic-uberjar-functionality-test
  (testing "Page loads"
    (go *driver* "http://localhost:3000")
    (wait-visible *driver* {:id :app})
    (wait 3)
    (is (has-text? *driver* "Servers"))))
