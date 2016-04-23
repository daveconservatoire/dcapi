(ns dcapi.test-shared
  (:require [dcapi.mysql :as mysql]))

(defonce connection
  (doto (mysql/create-connection {:host     "localhost"
                                  :user     "root"
                                  :password "root"
                                  :database "dcsite"
                                  :port     8889})
    (.connect)))
