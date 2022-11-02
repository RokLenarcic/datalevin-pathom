# Datalevin Pathom 3 Integration

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/datalevin-pathom.svg)](https://clojars.org/org.clojars.roklenarcic/datalevin-pathom)

Provides tools to integrate Datalevin with Pathom 3 and optionally Fulcro RAD.

The main input into these processes is a collection of maps that each describe an attribute. Based on those maps
you can generate some configuration for Pathom 3 and Datalevin. Library uses logging via org.clojure/tools.logging.

This library provides:
- generate Datalevin schema from attribute maps, apply the schema
- generate a Pathom3 dynamic resolver for attributes that are identities or their dependents
- support custom queries for some keys
- Fulcro form save/delete middleware. Fulcro RAD dependency isn't part of the deps, you'll have to require your own. This is to keep this part of the library optional.

**SEE EXAMPLE SECTION FOR USES**

**USING IMPORTS:**
- `[org.clojars.roklenarcic.datalevin-pathom :as dtlv-p3]`
- `[org.clojars.roklenarcic.datalevin-pathom.options :as o]`
- `[org.clojars.roklenarcic.datalevin-pathom.schema :as sch]`
- `[org.clojars.roklenarcic.datalevin-pathom.query :as q]`
- `[org.clojars.roklenarcic.datalevin-pathom.resolver :as res]`

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

# Env middleware

Instead of constructing the Pathom env map yourself you can use `dtlv-p3/wrap-env` function
to create or update a pathom env.

```clojure
(dtlv-p3/wrap-env datalevin-connections
  [(limit-by-owner-plugin all-attributes)])
```

It uses connections and plugins. We'll mention plugins later.

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

# Plugins (and custom queries)

There is another, potentially more flexible, solution for implementing custom queries.

There are two **OPTIONAL** keys in Pathom env that override resolve and query generating fn used for dynamic resolver.

- `q/query-fn`
- `res/resolve-fn`

These default to:

- `q/generate-attr-query`, which is also a multimethod
- `res/resolve-attr-pull`

Especially the query one is interesting as you can override queries generated for a particular attribute.

When using `wrap-env` env middleware you can specify a coll of plugins, which will wrap these default methods,
and so they lend very well to adding limitations.

There's two plugin types, one for each function, `q/plugin` and`res/plugin`, see docs for function
argument lists.


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
(defn parser []
  (rad.p3/new-processor
    config
    (-> (form/wrap-env save/middleware delete/middleware)
        (dtlv-p3/wrap-env datalevin-connections)
        (attr/wrap-env all-attributes))
    []
    [form/resolvers
     (dtlv-p3/automatic-resolvers all-attributes :my-schema)]))

Of course additional things will be needed, this is just a very basic one.

```

# Examples

Useful examples implementing common needs in application.

Imagine some entities have attribute `::m.user/owner` and we want limit some lookups (list and also by ident)
to only show those where owner is the current user. First we write a function that will modify a query
to restrain by user:

```clojure
(defn limit-by-owner [query env]
  (if-let [user-id (-> env :ring/request :session ::m.user/id)]
    (q/add-to-query query
                    {'?logged-in-user user-id}
                    '[?e ::m.user/owner ?owner]
                    '[?owner ::m.user/id ?logged-in-user])
    (throw (ex-info "A logged in user is required" {:reauth? true}))))
```

We see `add-to-query` function used, which adds pairs of var + var values to the query inputs
and adds where conditions. The expected input `query` is the `q/->query` structure.

We can now add a query for a list of all the tags user has like so:

```clojure
(defmethod q/generate-attr-query ::m.tags/all-tags
  [env db attr pattern node-resolver-input]
  (-> '[:find [(pull ?e pattern) ...]
        :in $ pattern
        :where [?e ::m.tags/tag _]]
      (q/->query [db pattern])
      (c/limit-by-owner env)))
```

We override `q/generate-attr-query` for attribute `::m.tags/all-tags`. This attribute needs to be in the list of all attributes
passed to the `automatic-resolvers` function. We also restrained to owned tags below. To recap:

- we implemented a list query
- we limited the results with a reusable function (instead of making it part of the initial query)

We use plugins to generically limit query results to owners like so

```clojure
(defn limit-by-owner-plugin
  [attributes]
  (let [affected-idents (->> attributes
                             (keep #(when (and (o/identity? %) (:com.fulcrologic.rad.attributes-options/owned-only %))
                                      (o/qualified-key %)))
                             set)]
    (q/plugin
      (fn outer [handler]
        (fn inner [env db attr pattern node-resolver-input]
          (cond-> (handler env db attr pattern node-resolver-input)
            (affected-idents (o/qualified-key attr))
            (limit-by-owner env)))))))
```
This plugin is passed to `wrap-env` when creating the parser. We chose `:com.fulcrologic.rad.attributes-options/owned-only`
ourselves, it has no special meaning otherwise. It is a marker on attributes so the plugin knows which attribute queries to 
wrap.

So imagine this attribute definition:

```clojure
(defattr id ::id :long
  {ao/identity? true
   dpo/native-id? true
   ::ao/owned-only true
   ao/schema :main})
```

Queries by ident for tags will be limited to owned tags only.

If you are using Fulcro form middleware for save/delete, then you'll probably want
to:

- add some data to each entity as it is saved (e.g. created-at, updated-at)
- add the owner user based on session rather than trust the incoming data
- reject writes to entity that is owned by another user

Here's an example of augmenting the Fulcro middleware with these features:

```clojure
(defn new-entity? [ident] (tempid/tempid? (second ident)))

(defn owner-info [env ident]
  (let [attr (get-in env [::attr/key->attribute (first ident)])]
    (when (::ao/owned-only attr)
      {::user (session/from-env env ::m.user/id)
       ::owner (when-not (new-entity? ident)
                 (common/get-owner env ident))})))

(defn rewrite-owner-limited-ident
  [env ident value]
  (if-let [{::keys [user owner]} (owner-info env ident)]
    (cond
      (= nil owner user)
      (throw (ex-info (str "Cannot save " ident " without a user in the session") {}))
      (not= user owner)
      (throw (ex-info (str "Cannot move " ident " to a another owner.") {}))
      :else
      value)
    value))

(defn add-created-at
  [env ident value]
  (assoc value ::attributes/created-at {:after (Date.)}))

(defmethod r.s.middleware/rewrite-value ::ident
  [env ident value]
  (let [attr (get-in env [::attr/key->attribute (first ident)])
        has-owner? (::ao/owned-only attr)]
    (cond->> value
      has-owner? (rewrite-owner-limited-ident env ident)
      (new-entity? ident) (add-created-at env ident))))

(defn save-middleware [attrs]
  (doseq [a attrs
          :when (ao/identity? a)]
    (derive (ao/qualified-key a) ::ident))
  (-> fm/save-middleware
      (blob/wrap-persist-images attrs)
      ;; This is where you would add things like form save security/schema validation/etc.

      ;; This middleware lets you morph values on form save
      (r.s.middleware/wrap-rewrite-values)))

(defn wrap-validate-delete-owner!
  [handler]
  (fn [{::form/keys [params] :as env}]
    (if-let [{::keys [user owner]} (owner-info env (first params))]
      (if (= user owner)
        (handler env)
        (throw (ex-info (str "Cannot delete entity owned by another user.") {})))
      (handler env))))

(def delete-middleware
  (-> fm/delete-middleware
      wrap-validate-delete-owner!))
```

The `owner-info` function provides us information about the owner of an entity and also the current user in the session.

The function returns nil when the attribute has no owner restrictions. We then use `wrap-rewrite-values` to insert user data
and created at into the incoming delta where appropriate.

The delete middleware is wrapped with a check if the owner and logged in user are one and the same.

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
