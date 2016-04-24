(ns knex.core-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [is are run-tests async testing deftest run-tests]]
            [cljs.core.async :refer [<!]]
            [knex.core :as knex]
            [dcapi.test-shared :as ts]))

(deftest test-query
  (async done
    (go
      (is (= (->> (knex/query ts/connection "Course") <!)
             {}))
      (done))))

(comment (run-tests))
