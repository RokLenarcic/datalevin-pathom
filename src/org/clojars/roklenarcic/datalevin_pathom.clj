(ns org.clojars.roklenarcic.datalevin-pathom
  (:require [clojure.tools.logging.readable :as logr]
            [datalevin.core :as d]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [org.clojars.roklenarcic.datalevin-pathom.resolvers :as resolvers]))

(defn schema-db
  "Return DB from env for schema name"
  [env schema-name]
  (some-> env o/connections schema-name d/db))

(defn close-dbs
  "Close Datalevin connections in pathom env."
  [env]
  (doseq [conn (vals (o/connections env))]
    (d/close conn)))

(defn automatic-resolvers
  "Returns a list of resolvers, a dynamic resolvers and generated sub resolvers created from attributes."
  [attributes schema-name]
  (let [attributes (filter #(= schema-name (o/schema %)) attributes)
        mr (resolvers/main-resolver attributes schema-name)
        subresolvers (concat (resolvers/sub-resolvers mr attributes) (resolvers/custom-sub-resolvers mr attributes))]
    (logr/tracef "Datalevin schema=%s generated main resolver %s" schema-name mr)
    (logr/tracef "Datalevin schema=%s generated subresolvers %s" schema-name subresolvers)
    (apply vector mr subresolvers)))

(defn add-to-attributes
  "Add properties to attributes. Expects coll of attributes and a map of qualified-key -> extra property ifn (map or function).

  Useful to add o/query-fn o/resolver-input o/resolver-output to attributes before they are used."
  [attributes qk->extra-props]
  (mapv #(if-some [m (qk->extra-props (o/qualified-key %))] (merge % m) %) attributes))

(defn wrap-env
  "Build a (fn [env] env') that adds connection data to an env. If `base-wrapper` is supplied, then it will be called
   as part of the evaluation, allowing you to build up a chain of environment middleware."
  ([connection-map] (wrap-env nil connection-map))
  ([base-wrapper connection-map]
   (fn [env]
     (cond-> (assoc env o/connections connection-map)
       base-wrapper (base-wrapper)))))
