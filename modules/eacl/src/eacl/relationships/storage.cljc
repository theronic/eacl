(ns eacl.relationships.storage
  "Canonical relationship-storage attribute identities shared by every backend.

  Relationship storage ABI 9 is independent of the EACL v8 library and
  permission representation. Serving adapters consume only this layout.")

(def version 9)
(def value-arity 5)
(def identity-arity 4)
(def tuple-types
  [:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref :db.type/ref])
(def qualifier-capability :nil-only)
(def format-id :eacl.relationship/endpoint-pair-v9-qualifier-ref)

(def forward-attribute
  :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier)

(def reverse-attribute
  :eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier)

(def attributes
  #{forward-attribute reverse-attribute})

(defn ^:no-doc relation-triples
  "Materializes [resource-type relation-eid subject-type] schema coordinates."
  [relation-datoms]
  (mapv (fn [{:keys [e v]}]
          [(nth v 0) e (nth v 2)])
        relation-datoms))

(defn ^:no-doc normalize-scan-options
  "Normalizes the `cursor-or-options` scan argument shared by every
  backend's ordered scans: a bare value is an exclusive ascending lower
  bound; a map may carry :direction, :bound-eid, :inclusive-bound?, and
  (where the backend pages natively) :limit. Every engine caller already
  passes a map naming its direction, which is validated and returned as
  is; consumers read :inclusive-bound? and :limit by truthiness."
  [cursor-or-options]
  (if (map? cursor-or-options)
    (let [direction (get cursor-or-options :direction :asc)]
      ;; `case` dispatches correctly on both platforms; keyword
      ;; `identical?` is false in ClojureScript for non-interned literals.
      (case direction
        (:asc :desc) nil
        (throw (ex-info "Scan direction must be :asc or :desc."
                        {:type :eacl/invalid-scan-options
                         :eacl/error :eacl/invalid-scan-options
                         :direction direction})))
      (if (contains? cursor-or-options :direction)
        cursor-or-options
        (assoc cursor-or-options :direction :asc)))
    {:direction :asc
     :bound-eid cursor-or-options
     :inclusive-bound? false
     :limit nil}))
