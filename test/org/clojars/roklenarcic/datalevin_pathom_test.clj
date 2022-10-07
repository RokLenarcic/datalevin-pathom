(ns org.clojars.roklenarcic.datalevin-pathom-test
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]
            [org.clojars.roklenarcic.test-db :as test-db]
            [org.clojars.roklenarcic.datalevin-pathom :as p]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [org.clojars.roklenarcic.datalevin-pathom.query :as q]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [datalevin.core :as d]))

(use-fixtures :each test-db/with-conn)

(deftest query-test
  (testing "Idents by keys"
    (let [_ (d/transact! test-db/*conn*
                         [{::a/id2 1, ::a/name "Frege"}
                          {::a/id2 2 ::a/name "Peirce"}
                          {::a/id2 3 ::a/name "De Morgan"}])
          base-env (-> {o/connections {:test test-db/*conn*}}
                       (pci/register (p/automatic-resolvers a/attributes :test)))
          processor (p.eql/boundary-interface base-env)]
      (is (= {[::a/id2 1] {::a/name "Frege"} [::a/id2 2] {::a/name "Peirce"}}
             (processor {} [{[::a/id2 1] [::a/name]} {[::a/id2 2] [::a/name]}]))))))

(deftest custom-query-test
  (testing "Custom query test"
    (let [_ (d/transact! test-db/*conn*
                         [{::a/id2 1, ::a/name "Frege"}
                          {::a/id2 2 ::a/name "Peirce"}
                          {::a/id2 3 ::a/name "De Morgan"}])
          attributes (conj a/attributes {o/qualified-key ::named-peirce
                                         o/type :ref
                                         o/target ::a/id2
                                         o/schema :test
                                         o/query-fn (fn [env conn pattern input]
                                                      (q/->query '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                                                                   :where [?e ::a/name ?v]]
                                                                 [conn pattern "Peirce"]))})
          base-env (-> {o/connections {:test test-db/*conn*}}
                       (pci/register (p/automatic-resolvers attributes :test)))
          processor (p.eql/boundary-interface base-env)]
      (is (= {::named-peirce [{::a/name "Peirce"}]
              [::a/id2 1] {::a/name "Frege"}}
             (processor {} [{::named-peirce [::a/name]} {[::a/id2 1] [::a/name]}])))
      (is (= {::named-peirce [{::a/name "Peirce"}]
              [::a/id2 1] {::a/name "Frege"}}
             (processor {} [{'(::named-peirce {:a 1}) [::a/name]} {[::a/id2 1] [::a/name]}])))))
  #_(testing "Custom query with function call"
    (let [_ (d/transact! test-db/*conn*
                         [{::a/id2 1, ::a/name "Frege"}
                          {::a/id2 2 ::a/name "Peirce"}
                          {::a/id2 3 ::a/name "De Morgan"}])
          attributes (conj a/attributes {o/qualified-key ::named-peirce
                                         o/type :ref
                                         o/target ::a/id2
                                         o/schema :test
                                         o/query-fn (fn [env conn pattern input]
                                                      (d/q '[:find [(pull ?e pattern) ...] :in $ pattern ?v
                                                             :where [?e ::a/name ?v]]
                                                           conn pattern "Peirce"))})
          base-env (-> {o/connections {:test test-db/*conn*}}
                       (pci/register (p/automatic-resolvers attributes :test)))
          processor (p.eql/boundary-interface base-env)]
      (is (= {::named-peirce [{::a/name "Peirce"}]
              [::a/id2 1] {::a/name "Frege"}}
             (processor {} [{::named-peirce [::a/name]} {[::a/id2 1] [::a/name]}]))))))
