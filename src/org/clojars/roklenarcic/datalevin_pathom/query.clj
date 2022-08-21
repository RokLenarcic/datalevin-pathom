(ns org.clojars.roklenarcic.datalevin-pathom.query
  "Datalevin queries and other transforms of results."
  (:require [com.wsscode.pathom3.connect.planner :as pcp]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]))

;;; native id handling

(defn pathom-ast->datalevin-pull
  "Takes the set of native ID attribute keys and AST and returns a query where :db/id is used in place of the native
  ID attributes."
  [native-id-attrs pathom-ast]
  (eql/ast->query
    (eql/transduce-children
      (map (fn [{:keys [key dispatch-key] :as node}]
             (if (native-id-attrs dispatch-key)
               (assoc node :dispatch-key :db/id :key (if (eql/ident? key) (assoc key 0 :db/id) :db/id))
               node)))
      pathom-ast)))

(defn- fix-id-keys
  "Fix the ID keys recursively on result."
  [native-id-attrs ast-nodes result]
  (let [join-key->children (into {}
                                 (comp
                                   (filter #(= :join (:type %)))
                                   (map (fn [{:keys [key children]}] [key children])))
                                 ast-nodes)
        find-native-key #(or (->> ast-nodes (map :dispatch-key) (some native-id-attrs)) %)]
    (reduce-kv
      (fn [m k v]
        (cond
          (= :db/id k) (assoc m (find-native-key k) v)
          (and (eql/ident? k) (= :db/id (first k))) (assoc m (assoc k 0 (find-native-key k)) v)
          (and (contains? join-key->children k) (vector? v)) (assoc m k (mapv #(fix-id-keys native-id-attrs (join-key->children k) %) v))
          (and (contains? join-key->children k) (map? v)) (assoc m k (fix-id-keys native-id-attrs (join-key->children k) v))
          :otherwise (assoc m k v)))
      {}
      result)))

(defn datalevin-result->pathom-result
  "Convert a datomic result containing :db/id into a pathom result containing the proper id keyword that was used
   in the original query."
  [native-id-attrs ast result]
  (when result
    (let [{:keys [children]} ast]
      (if (vector? result)
        (mapv #(fix-id-keys native-id-attrs children %) result)
        (fix-id-keys native-id-attrs children result)))))
;;;

;; RESOLVERS

(defn solo-attribute-with-params
  "Returns the [qkw-attr params] of AST node that is the single child of root note, and it is not
  an ident."
  [ast]
  (let [children (:children ast)]
    (when-let [node (and (= 1 (count children))
                         (= :root (:type ast))
                         (first children))]
      (when (= (:key node) (:dispatch-key node)) [(:key node) (:params node)]))))

(defn wrap-custom-query-fn
  "Wraps custom query-fn in logic that unwraps join in pattern that was created from
  foreign AST. The fact that we're using custom query already means that we have only 1 child
  in AST.

  It will also modify xf on returned query to add {:kw result} wrap."
  [query-fn kw]
  (fn custom-query-wrapper [env db pattern input]
    (let [result (query-fn env db (if (map? (first pattern)) (val (ffirst pattern)) pattern) input)]
      (update result
              :org.clojars.roklenarcic.datalevin-pathom/xf
              (fn [xf] (comp (fn [result] {kw result}) xf))))))

(defn custom-query-fn
  "Returns a custom query-fn based on env and foreign-ast and node-resolver-input."
  [qkw->custom-query foreign-ast node-resolver-input]
  (when-let [[kw params] (and (empty? node-resolver-input)
                              (solo-attribute-with-params foreign-ast))]
    (qkw->custom-query kw)))

(defn query-fn-handlers
  [attributes]
  ; ; kw -> fn/true/false/nil
  (let [base-map (zipmap (map o/qualified-key attributes)
                         (map #(o/query-fn % (when (o/identity? %) (o/native-id? % false))) attributes))]
    (reduce-kv
      (fn [m k v]
        (case v
          nil m
          true (assoc-in m [:native k] (fn native-query-fn [_ db pattern input]
                                         (o/->query '[:find (pull ?e pattern) . :in $ pattern ?e]
                                                    [db pattern (input k)])))
          false (assoc-in m [:other k] (fn id-query-fn [_ db pattern input]
                                         (o/->query [:find '(pull ?e pattern) '. :in '$ 'pattern '?v :where ['?e k '?v]]
                                                    [db pattern (input k)])))
          (assoc-in m [:custom k] (wrap-custom-query-fn v k))))
      {:custom {} :native {} :other {}}
      base-map)))

(defn query-factory
  "Returns a function that takes node-resolver-input and returns the query and the parameters."
  [attributes]
  ; #{:custom, :native, :other} -> kw -> query-fn
  (let [{:keys [custom other native]} (query-fn-handlers attributes)]
    ;; prefer custom queries, native id identities, normal identities
    (fn [env db pattern input]
      (let [f (or (custom-query-fn custom (-> env ::pcp/node ::pcp/foreign-ast) input)
                  (some native (keys input))
                  (some other (keys input))
                  (throw (ex-info "Couldn't find query for input" {:node (-> env ::pcp/node)})))]
        (f env db pattern input)))))
