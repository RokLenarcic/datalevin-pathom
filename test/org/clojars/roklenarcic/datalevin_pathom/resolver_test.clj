(ns org.clojars.roklenarcic.datalevin-pathom.resolver-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]
            [org.clojars.roklenarcic.datalevin-pathom.resolvers :as r]))

(deftest entities-map-test
  (is (= {::a/id [a/name-attr a/id-attr]
          ::a/id2 [a/ref-attr a/int-attr a/name-attr a/id2-attr]
          ::a/id3 [a/name-attr a/id3-attr]
          ::a/sub-table [a/addr-attr a/sub-table-attr]}
         (r/entities-map a/attributes))))


