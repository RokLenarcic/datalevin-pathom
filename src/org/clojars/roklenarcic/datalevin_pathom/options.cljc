(ns org.clojars.roklenarcic.datalevin-pathom.options
  (:refer-clojure :exclude [type]))

;; ENV

(def connections
  "If using the pathom-plugin, the resulting pathom-env will contain
    a map from schema->connection at this key path"
  :org.clojars.roklenarcic.datalevin-pathom/connections)

(def query-fn
  "Attribute key, used to specify custom query to use for this attribute. Value should be a function (fn [env conn pattern node-resolver-input]),
  where `pattern` is pull pattern constructed from AST.

  It can return either:
   - a map of query + query params, use ->query function for that
   - a query result, use ->query-result function for that"
  :org.clojars.roklenarcic.datalevin-pathom/query-fn)

(def resolver-input
  "ALIAS to :com.wsscode.pathom3.connect.operation/input.

  Defines expected input of resolver of an attribute that generates its own query, i.e. an attribute with query-fn.

  If not specified it defaults to []"
  :com.wsscode.pathom3.connect.operation/input)

(def resolver-output
  "ALIAS to :com.wsscode.pathom3.connect.operation/output.
  Defines the expected output of resolver of an attribute that generates its own query, i.e. an attribute with query-fn.

  If not specified it will be just the attribute if a scalar type, and [{attr [target/id]}] if :ref type."
  :com.wsscode.pathom3.connect.operation/output)

;; ATTRIBUTES

(def native-id?
  "If true it will map the given ID attribute (which must be type long) to :db/id."
  :org.clojars.roklenarcic.datalevin-pathom/native-id?)

(def attribute-schema
  "a map of schema attributes to be included in transacted schema.
    example:  {:db/isComponent true}
  "
  :org.clojars.roklenarcic.datalevin-pathom/attribute-schema)

(def schema
  "A keyword.

   Abstractly names a schema on which this attribute lives."
  :com.fulcrologic.rad.attributes/schema)

(def qualified-key
  "A Keyword. Keyword name of the attribute."
  :com.fulcrologic.rad.attributes/qualified-key)

(def type
  "A Keyword. Holds the data type name of the attribute.
  :string :enum :boolean :password :int :long :double
  :float :bigdec :bigint :decimal
  :instant :keyword :symbol :ref :uuid
  "
  :com.fulcrologic.rad.attributes/type)

(def identity?
  "Boolean. Indicates that the attribute is used to identify entities."
  :com.fulcrologic.rad.attributes/identity?)

(def identities
  "A set of qualified keys of attributes that serve as an identity for an entity. It is the list of attributes that
  when present on entity also have this attribute."
  :com.fulcrologic.rad.attributes/identities)

(def enumerated-values
  "REQUIRED For data type :enum. A `set` of keywords that define the complete list of allowed values in an
   enumerated attribute. See `enumerated-labels`."
  :com.fulcrologic.rad.attributes/enumerated-values)

(def target
  "REQUIRED for `:ref` attributes. A qualified keyword of an `identity? true` attribute that identifies the entities/rows/docs
   to which this attribute refers.

   If this attribute is a persisted edge (complex references can be resolved by resolvers and need not be reified in a database)
   then your database adapter will likely require other details so it can properly generate resolvers and save functionality."
  :com.fulcrologic.rad.attributes/target)

(def cardinality
  "OPTIONAL. Default `:one`. Can also be `:many`.

   This option indicates that this attribute either has a single value or a homogeneous set of values. It is an
   indication to reports/forms, and an indication to the storage layer about how the attribute will need to be
   stored.

   WARNING: Not all database adapters support `:many` on non-reference types. See your database adapter for details."
  :com.fulcrologic.rad.attributes/cardinality)
