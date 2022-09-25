(ns org.clojars.roklenarcic.datalevin-pathom.schema
  (:require [datalevin.core :as d]
            [clojure.tools.logging.readable :as logr]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]))

(def type-map
  {:string   :db.type/string
   :enum     :db.type/keyword
   :boolean  :db.type/boolean
   :password :db.type/string
   :int      :db.type/long
   :long     :db.type/long
   :double   :db.type/double
   :float    :db.type/float
   ;:bigdec   :db.type/bigdec
   ;:bigint   :db.type/bigint
   ;:decimal  :db.type/bigdec
   :bigdec   :db.type/string
   :bigint   :db.type/string
   :decimal  :db.type/string
   :instant  :db.type/instant
   :keyword  :db.type/keyword
   :symbol   :db.type/symbol
   ;:tuple    :db.type/tuple
   :ref      :db.type/ref
   :uuid     :db.type/uuid
   ;:uri      :db.type/uri
   })

(defn attributes-not-in-schema
  "Returns attributes in current schema, that are not in the supplied schema.

  Ignored :db/* attributes and attributes that have been created adhoc im the database."
  [conn schema]
  (let [current (reduce-kv
                  (fn [s attr-key schema]
                    (if (or (= "db" (namespace attr-key))
                            (= [:db/aid] (keys schema)))
                      s
                      (conj s attr-key)))
                  #{}
                  (d/schema conn))]
    (reduce disj current (keys schema))))

(defn- attribute-schema [attributes]
  (zipmap
    (map o/qualified-key attributes)
    (mapv
      (fn [a]
        (cond-> {:db/cardinality (if (= :many (o/cardinality a))
                                   :db.cardinality/many
                                   :db.cardinality/one)
                 :db/valueType (or (get type-map (o/type a))
                                   (throw (ex-info (str "No mapping from attribute type to Datalevin: " (o/type a)) {})))}
          (map? (o/attribute-schema a)) (merge (o/attribute-schema a))
          (o/identity? a) (assoc :db/unique :db.unique/identity)))
      attributes)))

(defn automatic-schema
  "Returns a schema of the supplied `attributes` with matching name.

  Skip attributes that are native-ids, and skip attributes that are not part of identity."
  [attributes schema-name]
  (let [attributes (filter #(and (= schema-name (o/schema %))
                                 (not (o/native-id? %))
                                 (or (o/identity? %) (seq (o/identities %))))
                           attributes)
        schema (attribute-schema attributes)]
    (when (empty? attributes)
      (logr/warn "Automatic schema requested, but the attribute list is empty. No schema will be generated!"))
    (logr/debug "Schema" schema-name "generated:" schema)
    schema))

(defn ensure-schema!
  "Ensure that the schema for the given attributes exists in the Datalevin database of the connection `conn`.

  * `conn` - The datalevin connection.
  * `schema-name` - Only attributes matching the given schema name will be transacted.
  * `attributes` - The attributes to ensure. Auto-filters attributes that do not match `schema-name`
  * `remove-attributes?` - if true removes attributes that are not in resulting schema, but are present
  in database
  See `verify-schema!` and `schema-problems` for validation functions on hand-managed schema.
  "
  [conn schema-name attributes remove-attributes?]
  (let [new-schema (automatic-schema attributes schema-name)
        attributes-to-remove (when remove-attributes? (attributes-not-in-schema conn new-schema))]
    (logr/trace "Updating schema" schema-name "to state" new-schema)
    (when (seq attributes-to-remove)
      (logr/debug "Removing" attributes-to-remove "from" schema-name))
    (d/update-schema conn new-schema attributes-to-remove)))

(comment (doseq [h (.getHandlers (Logger/getLogger ""))] (.setLevel h Level/FINEST))
         (.setLevel (Logger/getLogger "") Level/FINEST))
