(ns org.clojars.roklenarcic.datalevin-pathom.query
  "Datalevin queries and other transforms of results."
  (:require [clojure.tools.logging :as log]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]))

;;; native id handling

(defn expand-leaf-refs
  "When leaf in query is a ref, we expand the query to include target id, as that is what we
  realistically return."
  [pathom-ast key->attr]
  (eql/transduce-children
    (map (fn [{:keys [dispatch-key children] :as node}]
           (let [attr (key->attr dispatch-key)]
             (cond-> node
               (and (empty? children) (= (o/type attr) :ref))
               (assoc :children [{:type :prop, :dispatch-key (o/target attr), :key (o/target attr)}]
                      :type :join
                      :query [(o/target attr)])))))
    pathom-ast))

(defn pathom-ast->datalevin-pull
  "Takes the set of native ID attribute keys and AST and returns a query where :db/id is used in place of the native
  ID attributes."
  [native-id-attrs pathom-ast]
  (eql/ast->query
    (eql/transduce-children
      (map (fn [{:keys [key dispatch-key] :as node}]
             (cond-> (dissoc node :params)
               (native-id-attrs dispatch-key)
               (assoc :dispatch-key :db/id :key (if (eql/ident? key) (assoc key 0 :db/id) :db/id)))))
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
        (assoc m
          (cond
            (= :db/id k) (find-native-key k)
            (and (eql/ident? k) (= :db/id (first k))) (assoc k 0 (find-native-key k))
            :else k)
          (cond
            (and (contains? join-key->children k) (vector? v)) (mapv #(fix-id-keys native-id-attrs (join-key->children k) %) v)
            (and (contains? join-key->children k) (map? v)) (fix-id-keys native-id-attrs (join-key->children k) v)
            :otherwise v)))
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

;; PUBLIC

(defn solo-attribute-with-params
  "Returns the [qkw-attr params] of AST node that is the single child of root note, and it is not
  an ident."
  [ast]
  (let [children (:children ast)]
    (when-let [node (and (= 1 (count children))
                         (= :root (:type ast))
                         (first children))]
      (when (= (:key node) (:dispatch-key node)) [(:key node) (:params node)]))))

(defmulti generate-attr-query
  "Generates a map with all the pieces to run a DataLevin query."
  (fn [env db attr pattern node-resolver-input]
    (o/qualified-key attr)))

(defn ->query
  "Use this function to return your custom query from query-fn.
  First parameter is the query and second one is a sequence of params.

  xf is a transform that should be run on the output of the query

  e.g. (->query '[:find (pull ?e pattern) . :in $ pattern ?e] [db pattern (input k)] identity)"
  ([query query-params]
   (->query query query-params identity))
  ([query query-params xf]
   #:org.clojars.roklenarcic.datalevin-pathom
           {:query (vec query) :query-params (vec query-params) :xf xf}))

(defn add-to-query
  "Adds to an existing ->query map:
   {::query '[:find (pull ?e pattern) . :in $ pattern ?e]
    ::query-params [db pattern 5]}

    Param map of {'?e 1} would add ?e to :in clause and 5 to query-params.
    Where conditions are added as is."
  [query-map param-map & where-conditions]
  (-> query-map
      (update :org.clojars.roklenarcic.datalevin-pathom/query-params #(apply conj % (vals param-map)))
      (update :org.clojars.roklenarcic.datalevin-pathom/query
              (fn [query]
                (let [[select-part where-part] (split-with #(not= % :where) query)
                      where-part (concat where-part where-conditions)]
                  (vec
                    (concat select-part
                            (map symbol (keys param-map))
                            (if (contains? #{nil :where} (first where-part)) [] [:where])
                            where-part)))))))

(defmethod generate-attr-query :default
  [env db attr pattern node-resolver-input]
  (let [attr-kw (o/qualified-key attr)]
    (log/trace "Looking to generate query for " (pr-str attr) " " (o/identity? attr))
    (if (o/identity? attr)
      (if (o/native-id? attr)
        (->query '[:find (pull ?e pattern) . :in $ pattern ?e]
                   [db pattern (get node-resolver-input attr-kw)])
        (->query [:find '(pull ?e pattern) '. :in '$ 'pattern '?v :where ['?e attr-kw '?v]]
                   [db pattern (get node-resolver-input attr-kw)]))
      (if-let [query-fn (o/query-fn attr)]
        (query-fn env db pattern node-resolver-input)
        (throw (ex-info (str "Cannot generate query for attr " attr-kw ", it is not an ident.") {}))))))

(defn full-query-fn
  "Instantiates generate-attr-query + plugins combined fn."
  [plugins]
  (reduce
    (fn [f {:keys [plugin-handler]}]
      (plugin-handler f))
    generate-attr-query
    (filter #(= ::plugin (:plugin-type %)) plugins)))

(defn plugin
  "Create a plugin that wraps `generate-attr-query`. The 'f' needs to be a function
  (fn outer [delegate]
    (fn inner [env db attr pattern node-resolver-input]))"
  [f]
  {:plugin-type ::plugin
   :plugin-handler f})
