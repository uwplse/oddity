(defproject oddity "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring-server "0.4.0"]
                 [reagent "0.7.0"
                  :exclusions [cljsjs/react cljsjs/react-dom]]
                 [cljsjs/react-transition-group "2.4.0-0"
                  :exclusions [cljsjs/react cljsjs/react-dom]]
                 [cljsjs/react-popper "0.10.4-0"
                  :exclusions [cljsjs/react cljsjs/react-dom]]
                 [baking-soda "0.2.0"
                  :exclusions [cljsjs/react cljsjs/react-dom]]
                 [reagent-utils "0.2.1"]
                 [ring "1.6.1"]
                 [ring/ring-defaults "0.3.0"]
                 [compojure "1.6.0"]
                 [hiccup "1.0.5"]
                 [yogthos/config "0.8"]
                 [org.clojure/clojurescript "1.9.854"
                  :scope "provided"]
                 [secretary "1.2.3"]
                 [venantius/accountant "0.2.0"
                  :exclusions [org.clojure/tools.reader]]
                 [com.keminglabs/vomnibus "0.3.2"]
                 [data-frisk-reagent "0.4.5"]
                 [eval-soup "1.2.2"]
                 [org.clojure/core.async "0.3.443"]
                 [cljs-ajax "0.7.2"]
                 [metosin/compojure-api "2.0.0-alpha7"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.xerial/sqlite-jdbc "3.21.0"]
                 [com.cognitect/transit-cljs "0.8.243"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [org.clojure/data.json "0.2.6"]
                 [cljsjs/filesaverjs "1.3.3-0"]
                 [alandipert/storage-atom "2.0.1"]
                 [haslett "0.1.0"]
                 [aleph "0.4.6"]
                 [gloss "0.2.6"]
                 [manifold "0.1.6"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.danielsz/system "0.4.1"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [reloaded.repl "0.2.4"]
                 [org.clojure/tools.cli "0.3.5"]
                 [clj-http "3.8.0"]
                 [com.taoensso/tufte "2.0.1"]
                 [instaparse "1.4.9"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [clj-jaxb/lein-xjc "0.1.1"]]

  :plugins [[lein-environ "1.0.2"]
            [lein-cljsbuild "1.1.5"]
            [lein-asset-minifier "0.4.6"
             :exclusions [org.clojure/clojure]]
            [cljs-simple-cache-buster "0.2.1"]]

  :min-lein-version "2.5.0"

  :uberjar-name "oddity.jar"

  :main oddity.system

  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :dev :compiler :output-dir]
   [:cljsbuild :builds :dev :compiler :output-to]
   [:cljsbuild :builds :prod :compiler :output-dir]
   [:cljsbuild :builds :prod :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc"]
  :test-paths ["test/clj" "test/cljc"]
  :resource-paths ["resources" "target/prod"]

  :test-selectors {:default (complement :integration)
                   :integration :integration}

  :minify-assets [[:css {:source "resources/public/css/site.css" :target "resources/public/css/site.min.css"}]]

  :cljsbuild
  {:builds {:prod
            {:source-paths ["src/cljs" "src/cljc" "env/prod/cljs"]
             :compiler
             {:output-to "target/prod/public/js/app.js"
              :output-dir "target/prod/cljs-tmp"
              :optimizations :simple
              :pretty-print  false
              :foreign-libs [{:file "src/js/bundle.js"
                              :provides ["cljsjs.react" "cljsjs.react.dom" "react" "react_dom" "webpack.bundle"]}]}
              }
            :dev
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :figwheel {:on-jsload "oddity.core/mount-root"}
             :compiler
             {:main "oddity.dev"
              :asset-path "/js/out"
              :output-to "target/dev/public/js/app.js"
              :output-dir "target/dev/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true
              :foreign-libs [{:file "src/js/bundle.js"
                              :provides ["cljsjs.react" "cljsjs.react.dom" "react" "react_dom" "webpack.bundle"]}]}
             }}}

  :figwheel
  {:css-dirs ["resources/public/css"]}

  :repl-options {:init-ns user}

  :profiles {:dev {:repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

                   :dependencies [[binaryage/devtools "0.9.4"]
                                  [ring/ring-mock "0.3.1"]
                                  [ring/ring-devel "1.6.1"]
                                  [prone "1.1.4"]
                                  [figwheel-sidecar "0.5.18"]
                                  [cider/piggieback "0.3.10"]
                                  [pjstadig/humane-test-output "0.8.2"]
                                  [org.clojure/test.check "0.9.0"]
                                  [etaoin "0.2.9"]
                                  [nrepl "0.5.3"]]

                   :source-paths ["env/dev/clj"]
                   :plugins [[lein-figwheel "0.5.18"]
                             [org.clojure/tools.namespace "0.3.0-alpha4"
                              :exclusions [org.clojure/tools.reader]]
                             [refactor-nrepl "2.4.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]
                             ]

                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]

                   :env {:dev true}}

             :uberjar {:hooks [minify-assets.plugin/hooks]
                       :source-paths ["env/prod/clj"]
                       :prep-tasks ["compile" ["cljsbuild" "once" "prod"]]
                       :env {:production true}
                       :aot :all
                       :omit-source true}})
