(ns er-cassandra.drift.migration-helpers
  (:require [drift.config :as config]
            [er-cassandra.session]))

(defn session
  "get the alia session"
  []
  (:er-cassandra-session config/*config-map*))

(defmacro execute
  [& body]
  `(deref
    (er-cassandra.session/execute (session) ~@body)))

(defn pause
  []
  (println "pausing to let cassandra catch up")
  (Thread/sleep 5000))
