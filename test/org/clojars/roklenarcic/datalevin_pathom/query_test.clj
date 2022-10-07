(ns org.clojars.roklenarcic.datalevin-pathom.query-test
  (:require [clojure.test :refer :all]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.datalevin-pathom :as p]
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

(deftest add-to-query-test
  (testing "Modifies where"
    (let [q (q/->query '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                         :where [?e ::a/name ?v]]
                       [1 2 "Peirce"])
          q-no-where (q/->query '[:find [(pull ?e pattern) ...] :in $ pattern ?v]
                                [1 2 "Peirce"])]
      (is (= {::p/query '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                          :where [?e ::a/name ?v] [?e ::a/gender ?gender]]
              ::p/query-params [1 2 "Peirce"]
              ::p/xf identity}
             (q/add-to-query q {} '[?e ::a/gender ?gender])))
      (is (= {::p/query '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                          :where [?e ::a/gender ?gender]]
              ::p/query-params [1 2 "Peirce"]
              ::p/xf identity}
             (q/add-to-query q-no-where {} '[?e ::a/gender ?gender])))
      (is (= q-no-where (q/add-to-query q-no-where {})))
      (is (= q (q/add-to-query q {})))))
  (testing "Modifies params"
    (let [q (q/->query '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                         :where [?e ::a/name ?v]]
                       [1 2 "Peirce"])]
      (is (= {::p/query '[:find [(pull ?e pattern) ...] :in $ pattern ?v ?gender
                          :where [?e ::a/name ?v] [?e ::a/gender ?gender]]
              ::p/query-params [1 2 "Peirce" "male"]
              ::p/xf identity}
             (q/add-to-query q {:?gender "male"} '[?e ::a/gender ?gender]))))))
