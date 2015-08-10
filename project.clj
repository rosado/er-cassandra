(defproject employeerepublic/er-cassandra "0.2.8"
  :description "a simple cassandra conector"
  :url "https://github.com/employeerepublic/er-cassandra"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.cli "0.3.2"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [potemkin "0.4.1"]
                 [prismatic/plumbing "0.4.4"]
                 [mccraigmccraig/alia "2.7.2.1"]
                 [manifold "0.1.1-alpha3"]
                 [drift "1.5.3"]
                 [defurred "0.0.3"]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [expectations "2.1.2"]]
                   :plugins [[lein-expectations "0.0.8"]]}})
