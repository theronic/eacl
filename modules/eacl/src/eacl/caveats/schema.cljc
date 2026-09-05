(ns eacl.caveats.schema
  "Additive storage declarations for staged Caveats and sparse qualifiers."
  (:require [eacl.relationships.qualifier :as qualifier]))

(def caveat-attributes
  #{:eacl.caveat/name :eacl.caveat/parameters-payload
    :eacl.caveat/expression-source :eacl.caveat/profile-version})

(def datom-schema
  (mapv #(merge {:db/cardinality :db.cardinality/one} %)
        [{:db/ident qualifier/marker-attribute :db/valueType :db.type/long}
         {:db/ident qualifier/caveat-attribute :db/valueType :db.type/ref :db/index true}
         {:db/ident qualifier/context-attribute :db/valueType :db.type/string}
         {:db/ident qualifier/expiration-attribute :db/valueType :db.type/long}
         {:db/ident :eacl.caveat/name :db/valueType :db.type/string :db/unique :db.unique/identity}
         {:db/ident :eacl.caveat/parameters-payload :db/valueType :db.type/string}
         {:db/ident :eacl.caveat/expression-source :db/valueType :db.type/string}
         {:db/ident :eacl.caveat/profile-version :db/valueType :db.type/string}
         {:db/ident :eacl.relation/caveats :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
         {:db/ident :eacl.relation/allows-unqualified? :db/valueType :db.type/boolean}]))

(def datalevin-schema
  (into {} (map (fn [attr] [(:db/ident attr) (dissoc attr :db/ident)])) datom-schema))

(def datascript-schema
  (into {} (map (fn [[name attr]]
                 [name (if (= :db.type/ref (:db/valueType attr)) attr (dissoc attr :db/valueType))]))
        datalevin-schema))
