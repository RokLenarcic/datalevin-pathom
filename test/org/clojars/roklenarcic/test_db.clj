(ns org.clojars.roklenarcic.test-db
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]
            [org.clojars.roklenarcic.datalevin-pathom.schema :as sch]
            [datalevin.core :as d])
  (:import (java.io File)))

(def ^:dynamic *conn* nil)

(defn with-conn [f]
  (binding [*conn* (d/get-conn "test-db")]
    (try
      (sch/ensure-schema! *conn* :test a/attributes true)
      (f)
      (finally
        (d/close *conn*)
        (.delete (File. "test-db/data.mdb"))
        (.delete (File. "test-db/lock.mdb"))
        (.delete (File. "test-db"))))))
