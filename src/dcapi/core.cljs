(ns dcapi.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan <! put! promise-chan]]
            [om.next :as om]
            [dcapi.mysql :as mysql]))

(nodejs/enable-util-print!)

(defonce express (nodejs/require "express"))
(defonce http (nodejs/require "http"))

(defn express-get [app pattern f] (.get app pattern f))

(defonce connection
  (mysql/create-connection {:host     "localhost"
                            :user     "root"
                            :password "root"
                            :database "dcsite"
                            :port     8889}))

(def app (express))

(defn parser-read [env key params]
  (case key
    ))

(def parser (om/parser {:read parser-read}))

(express-get app "/api"
  (fn [req res]

    (.send res "Api")))

(defn -main []
  (.connect connection)
  (doto (.createServer http #(app %1 %2))
    (.listen 3000)))

(set! *main-cli-fn* -main)
