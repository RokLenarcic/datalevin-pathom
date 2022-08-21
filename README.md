# Datalevin Pathom 3 Integration

![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/datalevin-pathom.svg)

Provides tools to integrate Datalevin with Pathom 3 and optionally Fulcro RAD.

The main input into these processes is a collection of maps that each describe an attribute. Based on those maps
you can generate some configuration for Pathom 3 and Datalevin. Library uses logging via org.clojure/tools.logging.

This library provides:
- generate Datalevin schema from attribute maps, apply the schema
- generate a Pathom3 dynamic resolver for attributes that are identities or their dependents
- support custom queries for some keys
- Fulcro form save/delete middleware. Fulcro RAD dependency isn't part of the deps, you'll have to require your own. This is to keep this part of the library optional.

**USING IMPORTS:**
- `[org.clojars.roklenarcic.datalevin-pathom :as dtlv-p3]`
- `[org.clojars.roklenarcic.datalevin-pathom.options :as o]`
- `[org.clojars.roklenarcic.datalevin-pathom.schema :as sch]`

# Attributes

You describe your data model with attributes, a collection of maps. Each map describes one of your attributes.

You can find the supported keys in `org.clojars.roklenarcic.datalevin-pathom.options`, as vars with added docs.
The values of vars, i.e. the actual keys, **conveniently happen to have same values as Fulcro RAD attributes**. 

That fact doesn't change anything if you're integrating with Pathom 3 only, but if you are using also Fulcro RAD, then
**your Fulcro RAD attributes are also valid Datalevin-Pathom attributes**.

An example of an attribute:

```
(def id-attr
  {o/qualified-key ::id
   o/native-id? true
   o/identity? true
   o/schema :test
   o/type :long})
```

The main attribute is `o/qualified-key`, it names the attribute in the database. 

### Identity and non-identity attributes

If `o/identity?` is true then
the attribute is assumed to be basis for an entity. If `o/native-id?` is true, then the library will transparently
replace useages of this attribute with `:db/id` in the actual calls, otherwise it is assumed that the attribute will
be a `:db/unique :db.unique/identity` type of attribute that will be used to select the entity.

Attributes that are not identity attributes should name the identity attributes that have this attribute on the same entity.

E.g.

```
(def name-attr
  {o/qualified-key ::name
   o/identities #{::id ::id2 ::id3}
   o/schema :test
   o/type :string})
```

Name attribute is part of entities that are established by `::id` or `::id2` or `::id3`

### Attribute Schema

Each attribute is part of a schema. Each schema is expected to be a different database and the library will generally
split actions that involve multiple schemas to involve multiple DB calls.

# Schema

Using the Attributes a schema can be generated.

```
(sch/automatic-schema attributes :my-schema)
```

This will generate a schema from all attributes that have schema `:my-schema`. There's a convenience function
that will update the current schema with the generated, and optionally compare generated schema with the actual current schema,
and specify which attributes to remove.

```
(sch/ensure-schema! conn schema-name attributes remove-attributes?)
```

# Connections

Almost all the Pathom adjacent code of this library expects that **`env`** contains a map of Datalevin connections,
one for each `schema`. It is up to you how to enter this into the env, use the key via options namespace:

```
;; example env, contains :test schema connection
{o/connections {:test test-db/*conn*}}
```

# Dynamic resolver

Given attributes and schema name, a dynamic resolver can be created for you with sub-resolvers that will run queries on
attributes matching the schema. The supported queries are:
- queries starting with an ident (and expanding into related attributes)
- queries starting with an attribute that has a custom query defined

### Register resolvers

The resolvers can be registered using the normal method (note that we also provide connections):

```
;; env
(-> {o/connections {:my-schema ...conn}}
    (pci/register (dtlv-p3/automatic-resolvers a/attributes :my-schema)))
```

You can now resolve queries that start from an ident: `[{[:person/id 2] [:person/name]]`.

### Custom queries

Besides queries that start with an ident, you can also start with an attribute that has a custom query defined.

E.g. query `[{:person/over-18-old [:person/name :person/id]}]` to list people over 18. To make this work you have to provide
the query.

```
{o/qualified-key :person/over-18-old
 o/type :ref
 o/target :person/id
 o/schema :my-schema
 o/query-fn (fn [env conn pattern input]
              (o/->query '[:find [(pull ?e pattern) ...]
                           :in $ pattern ?v
                           :where [?e ::a/age ?age]
                                  [(<= 18 ?age)]]
                         [conn pattern "Peirce"]))}
```

The key takeaways:
- o/target on attribute specifies what entity this is referring to
- o/type is ref
- o/query-fn is provided

Query fn has parameters: 
- pathom env
- datalevin conn
- pull pattern that you should apply (otherwise joins won't work)
- `::pcr/node-resolver-input`

You should use `o/->query` for return value. Parameters are
- a query vector of query to run
- query params (`in` clause params) of query to run
- a fn transform function of query results (defaults to identity)

The options here might get expanded in the future.

### Custom resolver input and output

When using `o/query-fn` the subresolver for main dynamic resolver will look like this:

- `::pco/input` = `[]`
- `::pco/output` = `[{:person/over-18-old [:person/id]}]` or `[:attr]` if type is not `:ref`

You can change this by supplying `o/resolver-input` and/or `o/resolver-output` on your attribute.

### Attributes and CLJC context

When using Fulcro RAD or in some other context you will be putting attributes in a CLJC file to share them frontend/backend
for various reasons. Generally the attribute namespace is required by a lot of other namespace and you don't want
to put your query-fn logic into CLJC namespaces.

You can easily avoid these issues by only adding `o/query-fn` or `o/resolver-output` to the attribute just
before they are passed into `dtlv-p3/automatic-resolvers` function. Use helper function to achieve that:

```
(dtlv-p3/add-to-attributes a/attributes {:person/over-18-old {o/query-fn ....}})
```

It will merge supplied maps into their respective attributes.

# Fulcro RAD Form support

**This library doesn't pull a Fulcro RAD dependency, you have to add one yourself.**

Require `[org.clojars.roklenarcic.datalevin-pathom.fulcro-middleware :as fm]`.

Library provides save and delete middleware for form mutations. They expect presence of 
`::attr/key->attribute` in env, use `attr/wrap-env` to provide that. Here is an example of configuration that works:

Requires:
```
(:require   [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.form :as form]
            [com.fulcrologic.rad.pathom3 :as rad.p3]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.plugin :as p.plugin]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [org.clojars.roklenarcic.datalevin-pathom :as dtlv-p3]
            [org.clojars.roklenarcic.datalevin-pathom.fulcro-middleware :as fm]
            [org.clojars.roklenarcic.datalevin-pathom.options :as o]
            [myattributes :as a])
```

Here's the Pathom 3 setup:

```
(def env-wrappers (-> (attr/wrap-env a/attributes) ; adds needed ::attr/key->attribute
                      (form/wrap-env fm/save-middleware fm/delete-middleware))) ; adds form middlewares

(defn parser []
  (let [p (-> {o/connections {:my-schema conn}}
              ;; add dynamic resolver
              (pci/register (dtlv-p3/automatic-resolvers a/attributes :my-schema))
              ;; add form resolvers
              (pci/register (rad.p3/convert-resolvers form/resolvers))
              ;; optional error handling plugins
              (p.plugin/register-plugin rad.p3/attribute-error-plugin)
              (p.plugin/register-plugin rad.p3/rewrite-mutation-exceptions)
              ;; wrap env
              env-wrappers
              p.eql/boundary-interface)]
    (fn [env eql]
      (p env {:pathom/eql eql :pathom/lenient-mode? true}))))

```

## Other

Use `dtlv-p3/schema-db` to get connection from env for a schema. Use `dtlv-p3/close-dbs` to close all the connections in env when shutting down.

## Developing 

Invoke a library API function from the command-line:

    $ clojure -X org.clojars.roklenarcic.datalevin-pathom/foo :a 1 :b '"two"'
    {:a 1, :b "two"} "Hello, World!"

Run the project's tests:

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR:

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to org.clojars.roklenarcic/datalevin-pathom on clojars.org by default.

## License

The MIT License (MIT) Copyright © 2022, Rok Lenarčič

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
