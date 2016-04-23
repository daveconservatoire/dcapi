(ns dcapi.suite
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.test :refer-macros [run-all-tests]]
    dcapi.tests-to-run))

(nodejs/enable-util-print!)

(defn -main []
  (println "hello"))

(set! *main-cli-fn* -main)
