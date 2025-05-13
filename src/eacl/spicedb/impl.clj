(ns eacl.spicedb.impl
  "Mostly Spice-compatible layer for EACL."
  (:require [eacl.protocols :as proto :refer [IAuthorization]]
            [datomic.api :as d]
            [eacl.core2 :as eacl]))

; operation: :create, :touch, :delete unspecified

(defn find-relationship
  [db {:as relationship :keys [subject relation resource]}]
  ;; hmm subject & resource are spice-object. how to find?
  (d/q '[:find ?rel .
         :in $ ?subject ?relation ?resource
         :where
         [?rel :eacl.relationship/subject ?subject]
         [?rel :eacl.relationship/relation-name ?relation]
         [?rel :eacl.relationship/resource ?resource]]))

(defn tx-relationship
  [{:as _relationship :keys [subject relation resource]}]
  {:eacl.relationship/subject       subject
   :eacl.relationship/relation-name relation            ; is this name?
   :eacl.relationship/resource      resource})

(defn tx-update-relationship
  [db {:as update :keys [operation relationship]}]
  (case operation
    :touch ; ensure update existing. we don't have uniqueness on this yet.
    (let [rel-id (find-relationship db relationship)]
      (cond-> (tx-relationship relationship)
        rel-id (assoc :db/id rel-id)))

    :create
    (if-let [rel-id (find-relationship db relationship)]
      (throw (Exception. ":create relationship conflicts with existing: " rel-id))
      (tx-relationship relationship))

    :delete
    (if-let [rel-id (find-relationship db relationship)]
      [:db.fn/retractEntity rel-id]
      nil)

    :unspecified
    (throw (Exception. ":unspecified relationship update not supported."))))

(defn spiceomic-write-relationships!
  [conn updates]
  (->> updates
       (map tx-update-relationship (d/db conn))
       (d/transact conn)
       (deref)))

; Steps:
; - handle spice-object type & ID.

(defn spiceomic-can?
  [db subject permission resource]
  ; our impl. doesn't support spice-object yet, because we don't check subject/resource types,
  ; but we should.
  (eacl/can? db
             (:id subject)
             permission
             (:id resource)))

(defrecord Spiceomic [conn]
  IAuthorization
  (can? [this subject permission resource]
    ; how to resolve these?
    (spiceomic-can? (d/db conn) subject permission resource))

  (can? [this subject permission resource _consistency]
    ; todo: throw if consistency not fully-consistent.
    (spiceomic-can? (d/db conn) subject permission resource))

  (can? [this {:as demand :keys [subject permission resource consistency]}]
    (spiceomic-can? (d/db conn) subject permission resource))

  (read-schema [this]
    ; this can be read from DB.
    (throw (Exception. "not impl.")))

  (write-schema! [this schema]
    ; todo: potentially parse Spice schema.
    ; we'll need to support
    ; write-schema can take and validaet Relations.
    (throw (Exception. "not impl.")))

  (read-relationships [this filters]
    [])

  (write-relationships! [this updates]
    (spiceomic-write-relationships! conn updates)))

(defn make-client [conn]
  (->Spiceomic conn))

(comment
  ())