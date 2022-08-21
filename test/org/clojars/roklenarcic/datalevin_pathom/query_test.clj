(ns org.clojars.roklenarcic.datalevin-pathom.query-test
  (:require [clojure.test :refer :all]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [org.clojars.roklenarcic.datalevin-pathom.query :as q]))

(def native-id-attrs (into #{} (keep #(when (and (o/identity? %) (o/native-id? %)) (o/qualified-key %)) a/attributes)))

(deftest db-keys-query
  (testing "Updates query with db keys"
    (is (= [{[:db/id 1] [::a/name]}]
           (q/pathom-ast->datalevin-pull native-id-attrs (eql/query->ast [{[::a/id 1] [::a/name]}]))))
    (is (= [{[::a/id2 1] [::a/name]}]
           (q/pathom-ast->datalevin-pull native-id-attrs (eql/query->ast [{[::a/id2 1] [::a/name]}])))))
  (testing "Changes result from db to query keys"
    (is (= {[::a/id 1] {::a/name "HEHE"}}
           (q/datalevin-result->pathom-result native-id-attrs (eql/query->ast [{[::a/id 1] [::a/name]}]) {[:db/id 1] {::a/name "HEHE"}})))
    (is (= {[::a/id2 1] {::a/name "HEHE"}}
           (q/datalevin-result->pathom-result native-id-attrs (eql/query->ast [{[::a/id2 1] [::a/name]}]) {[::a/id2 1] {::a/name "HEHE"}})))))

(deftest solo-attribute-with-params
  (is (nil? (q/solo-attribute-with-params (eql/query->ast [{[::a/id 1] [::a/name]}]))))
  (is (nil? (q/solo-attribute-with-params (eql/query->ast [{::a/id [::a/name]} :x/b]))))
  (is (= [::a/id nil] (q/solo-attribute-with-params (eql/query->ast [{::a/id [::a/name]}]))))
  (is (= [::a/id {:a 1}] (q/solo-attribute-with-params (eql/query->ast [{'(::a/id {:a 1}) [::a/name]}])))))
