(ns org.clojars.roklenarcic.datalevin-pathom.schema-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.datalevin-pathom.schema :as s]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]))

(deftest schema-test
  (testing "Generation schema"
    (is (= {::a/addr #:db{:cardinality :db.cardinality/one
                          :valueType :db.type/string}
            ::a/id2 #:db{:cardinality :db.cardinality/one
                       :unique :db.unique/identity
                       :valueType :db.type/long}
            ::a/id3 #:db{:cardinality :db.cardinality/one
                         :unique :db.unique/identity
                         :valueType :db.type/uuid}
            ::a/name #:db{:cardinality :db.cardinality/one
                          :valueType :db.type/string}
            ::a/int-attr #:db{:cardinality :db.cardinality/many
                              :valueType :db.type/long}
            ::a/ref-attr #:db{:cardinality :db.cardinality/many
                              :valueType :db.type/ref}
            ::a/sub-table #:db{:cardinality :db.cardinality/one
                               :unique :db.unique/identity
                               :valueType :db.type/long}}
           (s/automatic-schema a/attributes :test)))))
