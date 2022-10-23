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

(defn parser [attributes]
  (p.eql/boundary-interface
    (-> {o/connections {:test test-db/*conn*}
         :com.fulcrologic.rad.attributes/key->attribute
         (zipmap (map o/qualified-key attributes) attributes)}
        (pci/register (p/automatic-resolvers attributes :test)))))

(deftest query-test
  (testing "Idents by keys"
    (let [_ (d/transact! test-db/*conn*
                         [{::a/id2 1, ::a/name "Frege"}
                          {::a/id2 2 ::a/name "Peirce"}
                          {::a/id2 3 ::a/name "De Morgan"}])
          processor (parser a/attributes)]
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
          processor (parser attributes)]
      (is (= {::named-peirce [{::a/name "Peirce"}]
              [::a/id2 1] {::a/name "Frege"}}
             (processor {} [{::named-peirce [::a/name]} {[::a/id2 1] [::a/name]}])))
      (is (= {::named-peirce [{::a/name "Peirce"}]
              [::a/id2 1] {::a/name "Frege"}}
             (processor {} [{'(::named-peirce {:a 1}) [::a/name]} {[::a/id2 1] [::a/name]}])))))
  (testing "Recursive relation"
    (let [_ (d/transact! test-db/*conn*
                         [{:db/id 5, ::a/name "Parent"}
                          {:db/id 6 ::a/name "Child" ::a/parent 5}
                          {:db/id 7, ::a/name "Parent" ::a/id2 100}
                          {:db/id 8 ::a/name "Child" ::a/id2 101 ::a/parent2 [::a/id2 100]}])
          processor (parser a/attributes)]
      ;; with subselects it works naturally
      (is (= {[::a/id 6] {::a/name "Child" ::a/parent {::a/id 5}}}
             (processor {} [{[::a/id 6] [::a/name {::a/parent [::a/id]}]}])))
      (is (= {[::a/id2 101] {::a/name "Child" ::a/parent2 {::a/id2 100}}}
             (processor {} [{[::a/id2 101] [::a/name {::a/parent2 [::a/id2]}]}])))
      ;; without subselects
      (is (= {[::a/id 6] {::a/name "Child" ::a/parent {::a/id 5}}}
             (processor {} [{[::a/id 6] [::a/name ::a/parent]}])))
      (is (= {[::a/id2 101] {::a/name "Child" ::a/parent2 {::a/id2 100}}}
             (processor {} [{[::a/id2 101] [::a/name ::a/parent2]}]))))))
