(ns er-cassandra.model.types
  (:require
   [schema.core :as s]
   [clj-time.core :as t]
   [er-cassandra.key :as k]))

(s/defschema CallbackFnSchema
  (s/make-fn-schema s/Any [[{s/Keyword s/Any}]]))

(s/defschema CallbacksSchema
  {(s/optional-key :after-load) [CallbackFnSchema]
   (s/optional-key :before-save) [CallbackFnSchema]})

(s/defschema KeySchema
  (s/either s/Keyword [s/Keyword]))

(s/defschema TableSchema
  {:name s/Keyword
   :key KeySchema})

(s/defschema LookupTableSchema
  (merge TableSchema
         {(s/optional-key :collection) (s/pred #{:list :set :map})}))

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
  [^Model model callback-key records]
  (let [callbacks (get-in model [:callbacks callback-key])]
    (reduce (fn [r callback]
              (mapv callback r))
            records
            callbacks)))

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
   (fn [r]
     (if-not (get r updated-at-col)
       (assoc r updated-at-col (.toDate (t/now)))
       r))))
