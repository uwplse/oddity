(defproject dviz "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring-server "0.4.0"]
                 [reagent "0.7.0"
                  :exclusions [cljsjs.react cljsjs.react-dom]]
                 [cljsjs/react-transition-group "1.1.3-0"]
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
                 [aleph "0.4.4"]
                 [gloss "0.2.6"]
                 [manifold "0.1.6"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.danielsz/system "0.4.1"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [reloaded.repl "0.2.4"]
                 [org.clojure/tools.cli "0.3.5"]
                 [clj-http "3.8.0"]]

  :plugins [[lein-environ "1.0.2"]
            [lein-cljsbuild "1.1.5"]
            [lein-asset-minifier "0.2.7"
             :exclusions [org.clojure/clojure]]
            [cljs-simple-cache-buster "0.2.1"]]

  :min-lein-version "2.5.0"

  :uberjar-name "dviz.jar"

  :main dviz.system

  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :dev :compiler :output-dir]
   [:cljsbuild :builds :dev :compiler :output-to]
   [:cljsbuild :builds :prod :compiler :output-dir]
   [:cljsbuild :builds :prod :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc"]
  :resource-paths ["resources" "target/prod"]

  :minify-assets
  {:assets
   {"resources/public/css/site.min.css" "resources/public/css/site.css"}}

  :cljsbuild
  {:builds {:prod
            {:source-paths ["src/cljs" "src/cljc" "env/prod/cljs"]
             :compiler
             {:output-to "target/prod/public/js/app.js"
              :output-dir "target/prod/cljs-tmp"
              :optimizations :advanced
              :pretty-print  false
              :foreign-libs [{:file "src/js/bundle.js"
                             :provides ["cljsjs.react" "cljsjs.react.dom" "webpack.bundle"]}]}
              }
            :dev
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :figwheel {:on-jsload "dviz.core/mount-root"}
             :compiler
             {:main "dviz.dev"
              :asset-path "/js/out"
              :output-to "target/dev/public/js/app.js"
              :output-dir "target/dev/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true
              :foreign-libs [{:file "src//js/bundle.js"
                             :provides ["cljsjs.react" "cljsjs.react.dom" "webpack.bundle"]}]}
             }}}

  :figwheel
  {:css-dirs ["resources/public/css"]}

  :repl-options {:init-ns user}

  :profiles {:dev {:repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

                   :dependencies [[binaryage/devtools "0.9.4"]
                                  [ring/ring-mock "0.3.1"]
                                  [ring/ring-devel "1.6.1"]
                                  [prone "1.1.4"]
                                  [figwheel-sidecar "0.5.15"]
                                  [com.cemerick/piggieback "0.2.2"]
                                  [pjstadig/humane-test-output "0.8.2"]
                                  [org.clojure/test.check "0.9.0"]
                                  ]

                   :source-paths ["env/dev/clj"]
                   :plugins [[lein-figwheel "0.5.15"]
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
