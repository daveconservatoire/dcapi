(ns dcapi.parser
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.next :as om]
            [clojure.string :as str]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [<! >! put! close!]]
            [cljs.core.async.impl.protocols :refer [Channel]]
            [dcapi.mysql :as mysql]))

;; SUPPORT FUNCTIONS

(defn chan? [v] (satisfies? Channel v))

(defn resolved-chan [v]
  (let [c (async/promise-chan)]
    (put! c v)
    c))

(defn read-chan-values [m]
  (if (first (filter chan? (vals m)))
    (let [c (async/promise-chan)
          in (async/to-chan m)]
      (go-loop [out {}]
        (if-let [[k v] (<! in)]
          (recur (assoc out k (if (chan? v) (<! v) v)))
          (>! c out)))
      c)
    (resolved-chan m)))

(defn read-chan-seq [s f]
  (go
    (let [out (async/chan 64)]
      (async/pipeline-async 10 out
                            (fn [in c]
                              (go
                                (let [in (if (chan? in) (<! in) in)
                                      out (f in)]
                                  (>! c (if (chan? out) (<! out) out)))
                                (close! c)))
                            (async/to-chan s))
      (<! (async/into [] out)))))

;;; DB PART

(def db-specs
  {:course {:name   "Course"
            :key    :course
            :fields #{:id :title :urltitle :author :description :homepage_order}}
   :topic  {:name   "Topic"
            :key    :topic
            :fields #{:id :title :urltitle :colour :courseId :sortorder}}})

(defmulti row-vattribute (fn [{:keys [ast]} {:keys [db/table]}] [table (:key ast)]))

(defmethod row-vattribute :default [env row] [:error :not-found])

(defn parse-row [{:keys [table ast] :as env} row]
  (let [accessors (into #{:id} (map :key) (:children ast))
        non-table (set/difference accessors (:fields table))
        virtual (filter #(contains? non-table (:key %)) (:children ast))
        row (assoc row :db/table (:key table))]
    (-> (reduce (fn [row {:keys [key] :as ast}]
                  (assoc row key (row-vattribute (assoc env :ast ast) row)))
                row
                virtual)
        (select-keys accessors)
        (read-chan-values))))

(defn query-sql [{:keys [table db] :as env} sql args]
  (if-let [{:keys [name] :as table-spec} (get db-specs table)]
    (go
      (let [rows (<! (mysql/query db sql args))
            env (assoc env :table table-spec)]
        (<! (read-chan-seq rows #(parse-row env %)))))
    [:error :invalid-table (str "[Query SQL] No specs for table " table)]))

(defn query-row [{:keys [table] :as env} id]
  (go
    (if-let [{:keys [name]} (get db-specs table)]
      (-> (query-sql env "SELECT * FROM ?? WHERE id = ?" [name id]) <!
          (first))
      [:error :invalid-table (str "[Query Row] No specs for table " table)])))

(defn query-table
  [{:keys [ast] :as env} table]
  (if-let [{:keys [name]} (get db-specs table)]
    (let [{:keys [limit where]} (:params ast)
          limit (or limit 50)
          where-clause (if where (str " WHERE " (str/join "," (map #(str "?? = ?") where))))]
      (query-sql (assoc env :table table)
                 (cond-> "SELECT * FROM ??"
                   where (str where-clause)
                   true (str " LIMIT ?"))
                 (concat [name] (flatten (seq where)) [limit])))
    [:error :invalid-table (str "[Query Table] No specs for table " table)]))

(defmethod row-vattribute [:course :topics]
  [env {:keys [id]}]
  (query-table (update-in env [:ast :params :where] #(assoc (or % {}) "courseId" id)) :topic))

(defmethod row-vattribute [:topic :course]
  [env {:keys [courseId]}]
  (query-row (assoc env :table :course) courseId))

(defmulti read om/dispatch)

(defmethod read :default [_ _ _] {:value [:error :not-found]})

(defmethod read :app/courses [{:keys [ast] :as env} _ params]
  {:value (query-table env :course)})

(defmethod read :app/topics [{:keys [ast] :as env} _ params]
  {:value (query-table env :topic)})

(def parser (om/parser {:read read}))

(defn parse [env tx]
  (-> (parser env tx) (read-chan-values)))
