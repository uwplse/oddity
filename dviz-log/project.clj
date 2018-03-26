(defproject dvizlog "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring-server "0.4.0"]
                 [ring "1.6.1"]
                 [ring/ring-defaults "0.3.0"]
                 [compojure "1.6.0"]
                 [hiccup "1.0.5"]
                 [com.keminglabs/vomnibus "0.3.2"]
                 [org.clojure/core.async "0.3.443"]
                 [metosin/compojure-api "2.0.0-alpha7"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.xerial/sqlite-jdbc "3.21.0"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [org.clojure/data.json "0.2.6"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.danielsz/system "0.4.1"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [reloaded.repl "0.2.4"]
                 [org.clojure/tools.cli "0.3.5"]
                 [clj-http "3.8.0"]
                 [ring-middleware-format "0.7.2"]]

  :plugins [[lein-environ "1.0.2"]]

  :min-lein-version "2.5.0"

  :uberjar-name "dviz-log.jar"

  :main dvizlog.system

  :source-paths ["src/clj"]
  :resource-paths ["resources" "target/prod"]

  :repl-options {:init-ns user}

  :profiles {:dev {:dependencies [[binaryage/devtools "0.9.4"]
                                  [ring/ring-mock "0.3.1"]
                                  [ring/ring-devel "1.6.1"]
                                  [prone "1.1.4"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [pjstadig/humane-test-output "0.8.2"]
                                  [org.clojure/test.check "0.9.0"]
                                  ]

                   :source-paths ["dev/"]
                   :plugins [[cider/cider-nrepl "0.17.0-SNAPSHOT"]
                             [org.clojure/tools.namespace "0.3.0-alpha4"
                              :exclusions [org.clojure/tools.reader]]
                             [refactor-nrepl "2.4.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]
                             ]

                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]

                   :env {:dev true}}

             :uberjar {:prep-tasks ["compile"]
                       :env {:production true}
                       :aot :all
                       :omit-source true}})
