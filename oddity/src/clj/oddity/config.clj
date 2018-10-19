(ns oddity.config)

(defn default-config []
  {:port 3000
   :debugger-port 4343
   :debugger-ws-port 5000
   :logger-ws-port 5001
   :usage-log-url nil
   :logger-db-spec {:classname   "org.sqlite.JDBC"
                    :subprotocol "sqlite"
                    :subname     ".usage-database.db"
                    }
   :enable-logging false
   :enable-debugger true
   :enable-traces false})

(defn client-config [config]
  (select-keys config [:enable-logging :enable-debugger :enable-traces]))
