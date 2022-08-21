(ns org.clojars.roklenarcic.datalevin-pathom.test-attributes
  (:require [clojure.test :refer :all]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]))

(def id-attr
  {o/qualified-key ::id
   o/native-id? true
   o/identity? true
   o/schema :test
   o/type :long})

(def id2-attr
  {o/qualified-key ::id2
   o/identity? true
   o/schema :test
   o/type :long})

(def id3-attr
  {o/qualified-key ::id3
   o/identity? true
   o/schema :test
   o/type :uuid})

(def name-attr
  {o/qualified-key ::name
   o/identities #{::id ::id2 ::id3}
   o/schema :test
   o/type :string})

(def int-attr
  {o/qualified-key ::int-attr
   o/identities #{::id2}
   o/type :int
   o/schema :test
   o/cardinality :many})

(def ref-attr
  {o/qualified-key ::ref-attr
   o/identities #{::id2}
   o/type :ref
   o/schema :test
   o/target ::sub-table
   o/cardinality :many})

(def sub-table-attr
  {o/qualified-key ::sub-table
   o/identity? true
   o/schema :test
   o/type :long})

(def addr-attr
  {o/qualified-key ::addr
   o/identities #{::sub-table}
   o/type :string
   o/schema :test})

(def attributes [id-attr id2-attr id3-attr name-attr int-attr ref-attr sub-table-attr addr-attr])

(def env-base {:com.fulcrologic.rad.attributes/key->attribute
               (zipmap (map o/qualified-key attributes) attributes)})
