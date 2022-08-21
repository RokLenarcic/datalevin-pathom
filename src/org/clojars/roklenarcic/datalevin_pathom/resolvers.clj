(ns org.clojars.roklenarcic.datalevin-pathom.resolvers
  (:require [clojure.tools.logging.readable :as logr]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.operation.protocols :as pop]
            [com.wsscode.pathom3.connect.planner :as pcp]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [org.clojars.roklenarcic.datalevin-pathom.query :as q]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [datalevin.core :as d]))

(defn namespace-for-schema
  "Returns namespace for all the generated resolvers and attributes."
  [schema-name]
  (if-some [ns (namespace schema-name)]
    (str ns "." (name schema-name))
    (name schema-name)))

(defn entities-map
  "Creates a map of entity attribute kw to {:id id-attr :members [member-attrs]}"
  [attributes]
  (reduce
    (fn [m attr]
      (cond
        (o/identity? attr) (update m (o/qualified-key attr) conj attr)
        (seq (o/identities attr)) (reduce #(update %1 %2 conj attr) m (o/identities attr))
        :else m))
    {}
    attributes))

(defn main-resolver [attributes schema-name]
  (let [native-id-attrs (into #{} (keep #(when (and (o/identity? %) (o/native-id? %)) (o/qualified-key %)) attributes))
        ->query (q/query-factory attributes)]
    (pco/resolver
      (symbol (namespace-for-schema (or schema-name :datalevin)) "datalevin-dyn")
      {::pco/cache? false
       ::pco/dynamic-resolver? true}
      (fn datalevin-resolve-internal
        [env {:keys [::pcr/node-resolver-input ::pcp/foreign-ast]}]
        (logr/tracef "Input %s Foreign AST: %s" node-resolver-input foreign-ast)
        (let [conn (-> env o/connections schema-name)
              pattern (q/pathom-ast->datalevin-pull native-id-attrs foreign-ast)
              {:org.clojars.roklenarcic.datalevin-pathom/keys [query query-params xf]} (->query env (d/db conn) pattern node-resolver-input)]
          (logr/debug "Using query" query)
          (logr/trace "Query params" (next query-params))
          (q/datalevin-result->pathom-result native-id-attrs foreign-ast (xf (apply d/q query query-params))))))))

(defn sub-resolvers
  "Resolvers for entities calculated from attributes. These map identity attributes to
  attributes that have them defined as identity. This does not apply to attributes that
  are parameter-less sources."
  [main-resolver attributes]
  (for [[attr-name attrs] (entities-map attributes)]
    (do (logr/debug "Sub resolver" attr-name "->" (map o/qualified-key attrs))
        (pco/resolver (symbol (namespace-for-schema attr-name) "datalevin-sub")
                      {::pco/dynamic-name (-> main-resolver pop/-operation-config ::pco/op-name)
                       ::pco/input [attr-name]
                       ::pco/output (mapv
                                      (fn [a]
                                        (if-let [t (o/target a)] {(o/qualified-key a) [t]} (o/qualified-key a)))
                                      attrs)}))))

(defn custom-sub-resolvers
  "Generate sub-resolvers for attributes that have custom queries. The unless otherwise specified the resolvers
  have no input required."
  [main-resolver attributes]
  (for [attr attributes
        :when (and (o/query-fn attr) (not (o/identity? attr)))]
    (pco/resolver (symbol (namespace-for-schema (o/qualified-key attr)) "datalevin-sub")
                  {::pco/dynamic-name (-> main-resolver pop/-operation-config ::pco/op-name)
                   ::pco/input (or (o/resolver-input attr) [])
                   ::pco/output (or (o/resolver-output attr)
                                    (if-let [t (o/target attr)] [{(o/qualified-key attr) [t]}] [(o/qualified-key attr)]))})))
