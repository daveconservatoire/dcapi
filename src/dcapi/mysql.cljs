(ns dcapi.mysql
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan <! put! promise-chan]]
            [goog.object :as gobj]))

(defonce mysql (nodejs/require "mysql"))

(defn create-connection [options] (.createConnection mysql (clj->js options)))

(defn convert-object [obj]
  (let [keys (array-seq (gobj/getKeys obj))]
    (reduce #(assoc % (keyword %2) (gobj/get obj %2)) {} keys)))

(defn query
  ([connection query] (query connection query []))
  ([connection query args]
   (let [c (promise-chan)]
     (.query connection query (clj->js args)
             (fn [err rows _]
               (put! c (or err (map convert-object (array-seq rows))))))
     c)))
