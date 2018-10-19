;; set up cider-jack-in
((nil
  (eval . (cider-register-cljs-repl-type 'dviz "(do (user/go) (user/cljs-repl))"))
  (cider-default-cljs-repl . dviz)))
