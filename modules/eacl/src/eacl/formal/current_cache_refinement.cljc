(ns eacl.formal.current-cache-refinement
  "Finite host specialization of the generated current-cache decision.

  The complete ten-entry table avoids generated-kernel dispatch in the cache
  hot path. Its exhaustive equivalence test and mutation control are the
  evidence; a checked-in digest of this source cannot add assurance.")

(def current-cache-domain
  (vec
   (for [stage [:eligibility :generation :exact-entry
                :exact-only-entry :managed-entry]
         available? [false true]]
     [stage available?])))

(def current-cache-mapping
  {[:eligibility false] :bypass-current-cache
   [:eligibility true] :probe-exact-entry
   [:generation false] :bypass-current-cache
   [:generation true] :probe-exact-entry
   [:exact-entry false] :probe-managed-entry
   [:exact-entry true] :use-exact-entry
   [:exact-only-entry false] :compute-exact-value
   [:exact-only-entry true] :use-exact-entry
   [:managed-entry false] :compute-selected-value
   [:managed-entry true] :use-managed-entry})

(defn complete-mapping?
  [mapping]
  (= (set current-cache-domain) (set (keys mapping))))

(defn action
  [stage available?]
  (get current-cache-mapping [stage available?]))
