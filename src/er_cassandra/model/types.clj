(ns er-cassandra.model.types
  (:require
   [cats.core :refer [mlet return]]
   [cats.context :refer [with-context]]
   [cats.labs.manifold :refer [deferred-context]]
   [schema.core :as s]
   [clj-time.core :as t]
   [er-cassandra.key :as k]))

(s/defschema CallbackFnSchema
  (s/make-fn-schema s/Any [[{s/Keyword s/Any}]]))

(s/defschema CallbacksSchema
  {(s/optional-key :after-load) [CallbackFnSchema]
   (s/optional-key :before-save) [CallbackFnSchema]})

(s/defschema KeySchema
  (s/either [(s/optional [s/Keyword] []) s/Keyword]
            [s/Keyword]))

(s/defschema SecondaryKeySchema
  s/Keyword)

;; the :key is the primary-key of the table, which may
;; have a compound parition key [[pk1 pk2] ck1 ck2]
;; the :entity-key is an optional unique identifier for which
;; a value taken from the primary record will be used to
;; delete with a secondary index from seocondary and lookup tables
;; TODO use the :entity-key to delete stale secondary and lookup records
(s/defschema TableSchema
  {:name s/Keyword
   :key KeySchema
   (s/optional-key :entity-key) SecondaryKeySchema})

;; some of the (non-partition) columns in a lookup-table
;; key may be collections... these will be expanded to the
;; the list of values formed by the cartesian product of all
;; the collection columns in the key. the
;; :collections metadata describes the type of each
;; collection column
(s/defschema LookupTableSchema
  (merge TableSchema
         {(s/optional-key :collections) {s/Keyword
                                         (s/pred #{:list :set :map})}}))

(s/defschema ModelSchema
  {:primary-table TableSchema
   (s/optional-key :unique-key-tables) [LookupTableSchema]
   (s/optional-key :secondary-tables) [TableSchema]
   (s/optional-key :lookup-key-tables) [LookupTableSchema]
   (s/optional-key :callbacks) CallbacksSchema})

(s/defrecord Model
    [primary-table :- TableSchema
     unique-key-tables :- [LookupTableSchema]
     secondary-tables :- [TableSchema]
     lookup-key-tables :- [LookupTableSchema]
     callbacks :- CallbacksSchema])

(defn ^:private force-key-seq
  [table]
  (assoc table :key (k/make-sequential (:key table))))

(defn ^:private force-key-seqs
  [model-schema table-seq-key]
  (assoc model-schema
         table-seq-key
         (mapv force-key-seq
               (get model-schema table-seq-key))))

(defn ^:private force-all-key-seqs
  "ensure all keys are given as sequences of components"
  [model-schema]
  (reduce force-key-seqs
          (assoc model-schema
                 :primary-table
                 (force-key-seq (:primary-table model-schema)))
          [:unique-key-tables
           :secondary-tables
           :lookup-key-tables]))

(s/defn ^:always-validate create-model :- Model
  "create a model record from a spec"
  [model-spec :- ModelSchema]
  (map->Model (merge {:unique-key-tables []
                      :secondary-tables []
                      :lookup-key-tables []
                      :callbacks {}}
                     (force-all-key-seqs model-spec))))

(defmacro defmodel
  [name model-spec]
  `(def ~name (create-model ~model-spec)))

(defn satisfies-primary-key?
  "return true if key is the same as the full primary-key"
  [primary-key key]
  (assert (sequential? primary-key))
  (assert (sequential? key))
  (= (flatten primary-key) (flatten key)))

(defn satisfies-partition-key?
  "return true if key is the same as the partition-key"
  [primary-key key]
  (assert (sequential? primary-key))
  (assert (sequential? key))
  (= (k/partition-key primary-key) key))

(defn satisfies-cluster-key?
  "return true if key matches the full partition-key plus
   some prefix of the cluster-key"
  [primary-key key]
  (assert (sequential? primary-key))
  (assert (sequential? key))
  (let [pkpk (k/partition-key primary-key)
        pkck (k/cluster-key primary-key)
        pkck-c (count pkck)

        kpk (take (count pkpk) (flatten key))
        kck (not-empty (drop (count pkpk) (flatten key)))
        kck-c (count kck)

        spk? (satisfies-partition-key? primary-key kpk)]
    (cond
      (> kck-c pkck-c)
      false

      (= kck-c pkck-c)
      (and spk?
           (= kck pkck))

      :else
      (do
        (and spk?
             (= kck
                (not-empty (take kck-c pkck))))))))

(defn- is-table-name
  [tables table]
  (some (fn [t] (when (= table (:name t)) t))
        tables))

(defn is-primary-table
  [^Model model table]
  (is-table-name [(:primary-table model)] table))

(defn is-secondary-table
  [^Model model table]
  (is-table-name (:secondary-tables model) table))

(defn is-unique-key-table
  [^Model model table]
  (is-table-name (:unique-key-tables model) table))

(defn is-lookup-key-table
  [^Model model table]
  (is-table-name (:lookup-key-tables model) table))

(defn uber-key
  [^Model model]
  (get-in model [:primary-table :key]))

(defn extract-uber-key-value
  [^Model model record]
  (let [kv (k/extract-key-value
            (get-in model [:primary-table :key])
            record)]
    (when (nil? kv)
      (throw (ex-info "nil uberkey" {:model model :record record})))
    kv))

(defn extract-uber-key-equality-clause
  [^Model model record]
  (k/extract-key-equality-clause
   (get-in model [:primary-table :key])
   record))

(defn run-callbacks
  ([^Model model callback-key records]
   (run-callbacks model callback-key records {}))
  ([^Model model callback-key records opts]
   (let [callbacks (concat (get-in model [:callbacks callback-key])
                           (get-in opts [callback-key]))]
     (reduce (fn [r callback]
               (mapv callback r))
             records
             callbacks))))

(defn run-callbacks-single
  ([^Model model callback-key record]
   (run-callbacks-single model callback-key record {}))
  ([^Model model callback-key record opts]
   (let [callbacks (concat (get-in model [:callbacks callback-key])
                           (get-in opts [callback-key]))]
     (reduce (fn [r callback]
               (callback r))
             record
             callbacks))))

(defn run-deferred-callbacks
  ([^Model model callback-key deferred-records]
   (run-deferred-callbacks model callback-key deferred-records {}))
  ([^Model model callback-key deferred-records opts]
   (with-context deferred-context
     (mlet [records deferred-records]
       (return
        (run-callbacks model callback-key records opts))))))

(defn run-deferred-callbacks-single
  ([^Model model callback-key deferred-record]
   (run-deferred-callbacks-single model callback-key deferred-record {}))
  ([^Model model callback-key deferred-record opts]
   (with-context deferred-context
     (mlet [record deferred-record]
       (return
        (run-callbacks-single model callback-key record opts))))))

(defn create-protect-columns-callback
  "create a callback which will remove cols from a record
   unless the confirm-col is set and non-nil. always
   removes confirm-col"
  [confirm-col & cols]
  (fn [r]
    (if (get r confirm-col)
      (apply dissoc r confirm-col cols)
      (dissoc r confirm-col))))

(defn create-updated-at-callback
  "create a callback which will add an :updated_at column
   if it's not already set"
  ([] (create-updated-at-callback :updated_at))
  ([updated-at-col]
   (fn [r] (assoc r updated-at-col (.toDate (t/now))))))

(defn create-select-view-callback
  "selects the given columns from a record"
  [cols]
  (fn [r]
    (select-keys r cols)))

(defn create-filter-view-callback
  "filers the given columns from a record"
  [cols]
  (fn [r]
    (apply dissoc r cols)))

(defn create-update-col-callback
  "a callback which updates a column with a function"
  [col f]
  (fn [r]
    (update r col f)))
