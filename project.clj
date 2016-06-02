(defproject dcapi "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :min-lein-version "2.5.3"

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.40"]
                 [figwheel-sidecar "0.5.0-2" :exclusions [clj-time joda-time org.clojure/tools.reader] :scope "test"]
                 [com.rpl/specter "0.9.3"]
                 [lein-doo "0.1.6" :scope "test"]
                 [org.omcljs/om "1.0.0-alpha36"]
                 [org.clojure/core.async "0.2.374"]]

  :plugins [[lein-cljsbuild "1.1.3"]]
  :source-paths ["src"]

  :clean-targets ["server.js" "target"]

  :cljsbuild {:builds [{:id           "dev"
                        :source-paths ["src"]
                        :figwheel     true
                        :compiler     {:main          dcapi.core
                                       :output-to     "target/server_dev/dcapi.js"
                                       :output-dir    "target/server_dev"
                                       :target        :nodejs
                                       :optimizations :none
                                       :source-map    true}}
                       {:id           "test"
                        :source-paths ["src" "test"]
                        :figwheel     true
                        :compiler     {:main          dcapi.suite
                                       :output-to     "target/server_test/dcapi.js"
                                       :output-dir    "target/server_test"
                                       :target        :nodejs
                                       :optimizations :none
                                       :source-map    true}}
                       {:id           "prod"
                        :source-paths ["src"]
                        :compiler     {:main          dcapi.core
                                       :output-to     "server.js"
                                       :output-dir    "target/server_prod"
                                       :target        :nodejs
                                       :optimizations :none}}]})
