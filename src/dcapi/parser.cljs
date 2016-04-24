(ns dcapi.parser
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.next :as om]
            [clojure.string :as str]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [<! >! put! close!]]
            [cljs.core.async.impl.protocols :refer [Channel]]
            [goog.string :as gstr]
            [knex.core :as knex]))

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

(defn read-chan-seq [f s]
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

(defn read-db-specs [connection]
  (go
    (let [read-table (fn read-table [table]
                       (go
                         (let [fields (->> (knex/raw connection "DESCRIBE ??" [table]) <!
                                           (map (comp keyword :Field))
                                           (set))]
                           {:name   table
                            :key    (keyword (-> (gstr/toSelectorCase (str table))
                                                 (.replace #"^-" "")))
                            :fields fields})))
          tables (->> (knex/raw connection "SHOW TABLES" []) <!
                      (map :Tables_in_dcsite))]
      (->> (map read-table tables)
           (read-chan-seq identity) <!
           (reduce #(assoc % (:key %2) %2) {})))))

;;; DB PART

(def db-specs
  {:playlist-item
   {:key    :playlist-item,
    :name   "PlaylistItem",
    :fields #{:youtubeid :title :id :relid :credit :sort :text}},

   :search-term
   {:key    :search-term
    :name   "SearchTerm"
    :fields #{:frequency :term :id}},

   :user-exercise-answer
   {:key    :user-exercise-answer,
    :name   "UserExerciseAnswer",
    :fields #{:exerciseId :attemptNumber :timeTaken :countHints :id :userId :complete
              :timestamp}},

   :topic
   {:key    :topic,
    :name   "Topic",
    :fields #{:urltitle :title :id :colour :sortorder :courseId}},

   :user-video-view
   {:key    :user-video-view,
    :name   "UserVideoView",
    :fields #{:status :id :userId :position :timestamp :lessonId}},

   :course
   {:key    :course,
    :name   "Course",
    :fields #{:description :urltitle :title :author :id :homepage_order}},

   :lesson
   {:key    :lesson,
    :name   "Lesson",
    :fields #{:description :topicno :urltitle :youtubeid :keywords :title :seriesno
              :lessonno :id :filetype :timestamp}},

   :user
   {:key    :user,
    :name   "User",
    :fields #{:email :firstip :subamount :name :biog :username :points :lastActivity :id
              :l1badgeCount :joinDate}}})

(defmulti row-vattribute (fn [{:keys [ast row]}] [(:db/table row) (:key ast)]))

(defmethod row-vattribute :default [env] [:error :not-found])

(defn parse-row [{:keys [table ast] :as env} row]
  (let [accessors (into #{:id} (map :key) (:children ast))
        non-table (set/difference accessors (:fields table))
        virtual (filter #(contains? non-table (:key %)) (:children ast))
        row (assoc row :db/table (:key table))]
    (-> (reduce (fn [row {:keys [key] :as ast}]
                  (assoc row key (row-vattribute (assoc env :ast ast :row row))))
                row
                virtual)
        (select-keys accessors)
        (read-chan-values))))

(defn query-sql [{:keys [table db] :as env} cmds]
  (if-let [{:keys [name] :as table-spec} (get db-specs table)]
    (go
      (let [rows (<! (knex/query db name cmds))
            env (assoc env :table table-spec)]
        (<! (read-chan-seq #(parse-row env %) rows))))
    [:error :invalid-table (str "[Query SQL] No specs for table " table)]))

(defn query-sql-first [env cmds]
  (go
    (-> (query-sql env cmds) <!
        (first)
        (or [:error :row-not-found]))))

(defn query-table
  [{:keys [ast] :as env} table]
  (if-let [{:keys [name]} (get db-specs table)]
    (let [{:keys [limit where]} (:params ast)
          limit (or limit 50)]
      (query-sql (assoc env :table table)
                 (cond-> [[:limit limit]]
                   where (conj [:where where]))))
    [:error :invalid-table (str "[Query Table] No specs for table " table)]))

;; RELATIONAL MAPPING

(defn has-one [env foreign-table local-field]
  (let [foreign-id (get-in env [:row local-field])]
    (query-sql-first (assoc env :table foreign-table) [[:where {:id foreign-id}]])))

(defn has-many [{:keys [row] :as env} foreign-table local-field]
  (query-table
    (update-in env [:ast :params :where]
               #(assoc (or % {}) (name local-field) (:id row)))
    foreign-table))

(defmethod row-vattribute [:course :topics] [env] (has-many env :topic :courseId))
(defmethod row-vattribute [:course :lessons] [env] (has-many env :lesson :seriesno))

(defmethod row-vattribute [:topic :course] [env] (has-one env :course :courseId))
(defmethod row-vattribute [:topic :lessons] [env] (has-many env :lesson :lessonno))

(defmethod row-vattribute [:lesson :course] [env] (has-one env :course :seriesno))
(defmethod row-vattribute [:lesson :topic] [env] (has-one env :topic :topicno))

(defmethod row-vattribute [:user :exercice-answer] [env] (has-many env :user-exercise-answer :userId))

(defmethod row-vattribute [:user-exercice-answer :user] [env] (has-one env :user :userId))

(defmethod row-vattribute [:user-video-view :user] [env] (has-one env :user :userId))

;; ROOT READS

(defn ast-key-id [ast] (some-> ast :key second))

(defn read [{:keys [ast] :as env} key params]
  (cond
    (= "by-id" (name key))
    (let [table (keyword (namespace key))]
      {:value (query-sql-first (assoc env :table table) [[:where {:id (ast-key-id ast)}]])})

    :else
    (case key
      :topic/by-slug {:value (query-sql-first (assoc env :table :topic)
                                              [[:where {:urltitle (ast-key-id ast)}]])}
      :app/courses {:value (query-table env :course)}
      :app/topics {:value (query-table env :topic)}

      {:value [:error :not-found]})))

(def parser (om/parser {:read read}))

(defn parse [env tx]
  (-> (parser env tx) (read-chan-values)))
