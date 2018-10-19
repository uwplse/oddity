(ns ^:figwheel-no-load oddity.dev
  (:require
    [oddity.core :as core]
    [devtools.core :as devtools]))

(devtools/install!)

(enable-console-print!)

(core/init!)
