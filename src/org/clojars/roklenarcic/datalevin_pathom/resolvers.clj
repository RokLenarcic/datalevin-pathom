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

(defmulti resolve-attr-pull
  "Generates and runs a query (or something similar) to resolve a tree, headed by the attribute.

  By default, it uses generate-attr-query and datalevin.core/query"
  (fn [env db attr pattern node-resolver-input]
    (o/qualified-key attr)))

(defmethod resolve-attr-pull :default
  [env db attr pattern node-resolver-input]
  (let [{:org.clojars.roklenarcic.datalevin-pathom/keys [query query-params xf]}
        (q/generate-attr-query env db attr pattern node-resolver-input)]
    (logr/debugf "Running query %s with params %s" query (rest query-params))
    (when query (xf (apply d/q query query-params)))))

(defn main-resolver [attributes schema-name]
  (let [native-id-attrs (into #{} (keep #(when (and (o/identity? %) (o/native-id? %)) (o/qualified-key %)) attributes))
        id-keys (into #{} (map o/qualified-key (filter o/identity? attributes)))
        key->attr (zipmap (map o/qualified-key attributes) attributes)
        resolver-sym (symbol (namespace-for-schema (or schema-name :datalevin)) "datalevin-dyn")]
    (logr/debugf "Generating Dynamic resolver %s" resolver-sym)
    (pco/resolver
      resolver-sym
      {::pco/cache? false
       ::pco/dynamic-resolver? true}
      (fn datalevin-resolve-internal
        [env {:keys [::pcr/node-resolver-input ::pcp/foreign-ast]}]
        (logr/tracef "Input %s Foreign AST: %s" node-resolver-input foreign-ast)
        (let [conn (-> env o/connections schema-name)
              pattern (q/pathom-ast->datalevin-pull native-id-attrs foreign-ast)
              _ (logr/trace "Original pattern " pattern)
              ident-key (some id-keys (keys node-resolver-input))
              solo-attr-key (first (q/solo-attribute-with-params (-> env ::pcp/node ::pcp/foreign-ast)))
              pattern (if (and (not ident-key) solo-attr-key (map? (first pattern)))
                        (val (ffirst pattern)) pattern)
              _ (logr/trace "Final pattern " pattern)
              attr (key->attr (or ident-key solo-attr-key))]
          (cond->> (resolve-attr-pull env (d/db conn) attr pattern node-resolver-input)
            (and (not ident-key) solo-attr-key) (assoc {} (o/qualified-key attr))
            :always (q/datalevin-result->pathom-result native-id-attrs foreign-ast)))))))

(defn sub-resolvers
  "Resolvers for entities calculated from attributes. These map identity attributes to
  attributes that have them defined as identity. This does not apply to attributes that
  are parameter-less sources."
  [main-resolver attributes]
  (for [[attr-name attrs] (entities-map attributes)]
    (do (logr/debug "Generating sub resolver" attr-name "->" (map o/qualified-key attrs))
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
        :when (not (or (o/identity? attr) (seq (o/identities attr))))]
    (do (logr/debug "Generating sub resolver" (o/qualified-key attr))
        (pco/resolver (symbol (namespace-for-schema (o/qualified-key attr)) "datalevin-sub")
                      {::pco/dynamic-name (-> main-resolver pop/-operation-config ::pco/op-name)
                       ::pco/input (or (o/resolver-input attr) [])
                       ::pco/output (or (o/resolver-output attr)
                                        (if-let [t (o/target attr)] [{(o/qualified-key attr) [t]}] [(o/qualified-key attr)]))}))))
