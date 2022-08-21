(ns org.clojars.roklenarcic.datalevin-pathom.fulcro-middleware-test
  (:require [clojure.test :refer :all]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.form :as form]
            [org.clojars.roklenarcic.datalevin-pathom.fulcro-middleware :as fm]
            [org.clojars.roklenarcic.datalevin-pathom.test-attributes :as a]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [org.clojars.roklenarcic.test-db :as test-db]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.plugin :as p.plugin]
            [org.clojars.roklenarcic.datalevin-pathom :as p]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.rad.pathom3 :as rad.p3]))

(def env-wrappers (-> (attr/wrap-env a/attributes)
                      (form/wrap-env fm/save-middleware fm/delete-middleware)))

(defn parser []
  (let [p (-> {o/connections {:test test-db/*conn*}}
              (pci/register (p/automatic-resolvers a/attributes :test))
              (pci/register (rad.p3/convert-resolvers form/resolvers))
              (p.plugin/register-plugin rad.p3/attribute-error-plugin)
              (p.plugin/register-plugin rad.p3/rewrite-mutation-exceptions)
              env-wrappers
              p.eql/boundary-interface)]
    (fn [env eql]
      (p env {:pathom/eql eql :pathom/lenient-mode? true}))))

(defn ->delta [entity id-key]
  {::form/master-pk id-key
   ::form/id (id-key entity)
   ::form/delta {[id-key (id-key entity)]
                 (reduce-kv
                   (fn [m k v]
                     (assoc m k {:after v}))
                   {}
                   entity)}})

(use-fixtures :each test-db/with-conn)

(deftest middleware-test
  (let [p (parser)]
    (let [tid (tempid/tempid)
          r1 (p {} `[(form/save-as-form ~(->delta {::a/id3 tid ::a/name "Rok X"} ::a/id3))])
          {{:keys [tempids] ::a/keys [id3]} `form/save-as-form} r1]
      (is (= [`form/save-as-form] (keys r1)))
      (is (= {tid id3} tempids))
      (is (= {[::a/id3 id3] {::a/id3 id3 ::a/name "Rok X"}}
             (p {} [{[::a/id3 id3] [::a/id3 ::a/name]}])))
      (is (= {`form/delete-entity {}} (p {} [`(form/delete-entity [::a/id3 ~id3])])))
      (is (= {[:org.clojars.roklenarcic.datalevin-pathom.test-attributes/id3 id3] {::a/id3 id3}}
             (p {} [{[::a/id3 id3] [::a/id3 ::a/name]}]))))))
