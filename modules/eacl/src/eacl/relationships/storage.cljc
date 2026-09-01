(ns eacl.relationships.storage
  "Canonical relationship-storage attribute identities shared by every backend.

  The `v7` keyword namespace is the persisted storage ABI, not an executable
  v7 engine contract. EACL v8 retains it so existing tuple data does not need
  to be rewritten merely to upgrade the library.")

(def forward-attribute
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def reverse-attribute
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

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
  (where the backend pages natively) :limit."
  [cursor-or-options]
  (if (map? cursor-or-options)
    (let [{:keys [direction bound-eid inclusive-bound? limit]
           :or {direction :asc}} cursor-or-options]
      ;; `case` dispatches correctly on both platforms; keyword
      ;; `identical?` is false in ClojureScript for non-interned literals.
      (case direction
        (:asc :desc) nil
        (throw (ex-info "Scan direction must be :asc or :desc."
                        {:type :eacl/invalid-scan-options
                         :eacl/error :eacl/invalid-scan-options
                         :direction direction})))
      {:direction direction
       :bound-eid bound-eid
       :inclusive-bound? (boolean inclusive-bound?)
       :limit limit})
    {:direction :asc
     :bound-eid cursor-or-options
     :inclusive-bound? false
     :limit nil}))
