(ns org.clojars.roklenarcic.datalevin-pathom.fulcro-middleware
  (:require [com.fulcrologic.rad.form :as form]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.ids :refer [new-uuid]]
            [com.fulcrologic.rad.middleware.save-middleware :as r.s.middleware]
            [com.fulcrologic.rad.type-support.decimal :as math]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer [pprint]]
            [datalevin.core :as d]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [taoensso.encore :as enc]))

(defn deep-merge
  "Merges nested maps without overwriting existing keys."
  [& xs]
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn reify-middleware
  "Create a 2 arity middleware with an operation and merge"
  ([operation]
   (fn [pathom-env]
     (let [op-result (operation pathom-env)]
       op-result)))
  ([operation handler]
   (fn [pathom-env]
     (let [op-result (operation pathom-env)]
       (deep-merge (handler pathom-env) op-result)))))

(defn schemas-for-delta [{::attr/keys [key->attribute]} delta]
  (let [all-keys (reduce-kv
                   (fn [s k v] (apply conj s (first k) (keys v)))
                   #{}
                   delta)]
    (into #{} (keep #(-> % key->attribute o/schema)) all-keys)))

(defn native-ident?
  "Returns true if the given ident is using a database native ID (:db/id)"
  [{::attr/keys [key->attribute] :as env} ident]
  (boolean (some-> ident first key->attribute o/native-id?)))

(defn uuid-ident?
  "Returns true if the ID in the given ident uses UUIDs for ids."
  [{::attr/keys [key->attribute] :as env} ident]
  (= :uuid (some-> ident first key->attribute ::attr/type)))

(defn tempid->txid [tempid] (str (:id tempid)))

(defn failsafe-id
  "Returns a fail-safe id for the given ident in a transaction. A fail-safe ID will be one of the following:
  - A long (:db/id) for a pre-existing entity.
  - A string that stands for a temporary :db/id within the transaction if the id of the ident is temporary.
  - A lookup ref (the ident itself) if the ID uses a non-native ID, and it is not a tempid.
  - A keyword if it is a keyword (a :db/ident)
  "
  [env ident]
  (if (keyword? ident)
    ident
    (let [id (second ident)]
      (cond
        (tempid/tempid? id) (tempid->txid id)
        (and (native-ident? env ident) (pos-int? id)) id
        :otherwise ident))))

(defn fix-numerics
  "Using field types double and float can cause anomolies with Datomic because js might send an int when the number has
   no decimal digits."
  [attr v]
  (case (:attr/type attr)
    :double (double v)
    :float (double v)
    :numeric (math/numeric v)
    :int (long v)
    :long (long v)
    v))

(defn to-one? [{::attr/keys [key->attribute]} k]
  (not (boolean (some-> k key->attribute (attr/to-many?)))))

(defn ref? [{::attr/keys [key->attribute]} k]
  (= :ref (some-> k key->attribute o/type)))

(defn tx-value
  "Convert `v` to a transaction-safe value based on its type and cardinality."
  [{::attr/keys [key->attribute] :as env} k v]
  (if (ref? env k)
    (failsafe-id env v)
    (let [attr (key->attribute k)]
      (fix-numerics attr v))))

(defn schema-value? [{::attr/keys [key->attribute]} target-schema k]
  (let [attr (key->attribute k)]
    (and (= (o/schema attr) target-schema) (not (o/identity? attr)))))

(defn to-one-txn
  "Save non-identity attributes."
  [env schema delta]
  (for [[ident entity-delta] delta
        [k {:keys [before after]}] entity-delta
        :when (and (schema-value? env schema k) (to-one? env k) (or (some? before) (some? after)))]
    (if (nil? before)
      [:db/add (failsafe-id env ident) k (tx-value env k after)]
      [:db/retract (failsafe-id env ident) k (tx-value env k before)])))

(defn to-many-txn
  "Save non-identity attributes."
  [env schema delta]
  (vec
    (mapcat
      (fn [[ident entity-delta]]
        (reduce
          (fn [tx [k {:keys [before after]}]]
            (if (and (schema-value? env schema k) (not (to-one? env k)))
              (let [before  (into #{} (map (fn [v] (tx-value env k v))) before)
                    after   (into #{} (map (fn [v] (tx-value env k v))) after)
                    adds    (map
                              (fn [v] [:db/add (failsafe-id env ident) k v])
                              (set/difference after before))
                    removes (map
                              (fn [v] [:db/retract (failsafe-id env ident) k v])
                              (set/difference before after))]
                (into tx (concat adds removes)))
              tx))
          []
          entity-delta))
      delta)))

(defn non-native-id-attributes-txn
  "When using non-native IDs, they have to be established in an ADD, and we need to supply a generated value for to replace
  the usual temp ID mechanism.

  non-native attribute like [:person/id temp-id] must add a fact that [:db/id TEMP_ID :person/id GENERATED_ID]
  so far the only ID we generate like that are UUIDs. We must also return TEMP_ID -> GENERATED_ID mapping when we return
  "
  [env delta]
  ;; this is stringified temp ID
  (let [txn (keep
              (fn [[k id :as ident]]
                (when (tempid/tempid? id)
                  (if (uuid-ident? env ident)
                    [:db/add (tempid->txid id) k (new-uuid)]
                    (throw (ex-info (format "Don't know how to generate ID for temp ID for attr %s." k)
                                    {:k k})))))
              (keys delta))]
    {:tempid->generated-id (zipmap (map second txn) (map #(nth % 3) txn))
     :txn txn}))

(defn save-form!
  "Do all the possible Datalevin operations for the given form delta (save to all
   Datalevin databases involved). If you include `:datalevin/transact` in the `env`, then
   that function will be used to transact datoms instead of the default (Datalevin) function."
  [{:datalevin/keys [transact] ::form/keys [params] :as env}]
  (let [{::form/keys [delta]} params
        schemas (schemas-for-delta env delta)
        all-tempids (->> (map second (keys delta))
                         (filter tempid/tempid?)
                         (into {} (map (juxt identity tempid->txid))))
        result  (volatile! {})]
    (doseq [schema schemas
            :let [connection (-> env o/connections (get schema))
                  {:keys [tempid->generated-id txn]} (non-native-id-attributes-txn env delta)
                  txn (concat
                        txn
                        (to-one-txn env schema delta)
                        (to-many-txn env schema delta))]]
      (when (log/enabled? :trace)
        (log/trace "Saving form delta" (with-out-str (pprint delta)))
        (log/trace "on schema" schema)
        (log/trace "Running txn\n" (with-out-str (pprint txn))))
      (if (and connection (seq txn))
        (try
          (let [tx! (or transact d/transact!)
                {:keys [tempids]} (tx! connection txn)
                tempid->real-id (reduce-kv (fn [m tempid tx-id]
                                             (if-let [real-id (tempid->generated-id tx-id (tempids tx-id))]
                                               (assoc m tempid real-id) m))
                                           {}
                                           all-tempids)]
            (vswap! result merge tempid->real-id))
          (catch Exception e
            (log/error e "Transaction failed!")
            {}))
        (log/error "Unable to save form. Either connection was missing in env, or txn was empty.")))
    {:tempids @result}))

(defn delete-entity!
  "Delete the given entity, if possible. `env` should contain the normal datomic middleware
   elements, and can also include `:datalevin/transact` to override the function that is used
   to transact datoms."
  [{:datalevin/keys [transact] ::form/keys [params]
    ::attr/keys   [key->attribute] :as env}]
  (enc/if-let [pk (ffirst params)
               id (get params pk)
               ident      [pk id]
               connection (-> env o/connections (get (o/schema (key->attribute pk))))
               tx! (or transact d/transact!)]
              (do
                (log/info "Deleting" ident)
                (tx! connection [[:db.fn/retractEntity (if (native-ident? env ident) id ident)]])
                {})
              (log/warn "Datalevin adapter failed to delete " params)))

(def save-middleware (r.s.middleware/wrap-rewrite-values (reify-middleware save-form!)))
(def delete-middleware (reify-middleware delete-entity!))
