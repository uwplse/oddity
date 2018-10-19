(ns ^:figwheel-no-load dviz.dev
  (:require
    [dviz.core :as core]
    [devtools.core :as devtools]))

(devtools/install!)

(enable-console-print!)

(core/init!)
