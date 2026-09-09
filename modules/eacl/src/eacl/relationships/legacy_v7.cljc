(ns eacl.relationships.legacy-v7
  "Frozen source codec used only by explicit offline migrations. Serving
  adapters never read these attributes."
  (:require [eacl.relationships.storage :as storage]))

(def version 7)
(def forward-attribute :eacl.v7.relationship/subject-type+relation+resource-type+resource)
(def reverse-attribute :eacl.v7.relationship/resource-type+relation+subject-type+subject)
(def attributes #{forward-attribute reverse-attribute})
(def tuple-types [:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref])

(defn endpoint-value [owner-type relation-eid endpoint-type endpoint-eid]
  [owner-type relation-eid endpoint-type endpoint-eid])

(defn source-schema
  "Projects target attribute definitions back to the immutable v7 source ABI."
  [definitions]
  (mapv (fn [definition]
          (if-let [ident ({storage/forward-attribute forward-attribute
                           storage/reverse-attribute reverse-attribute}
                          (:db/ident definition))]
            (assoc definition :db/ident ident :db/tupleTypes tuple-types)
            definition))
        definitions))
