;; set up cider-jack-in
((nil
  (eval . (cider-register-cljs-repl-type 'oddity "(do (user/go) (user/cljs-repl))"))
  (cider-default-cljs-repl . oddity)))
