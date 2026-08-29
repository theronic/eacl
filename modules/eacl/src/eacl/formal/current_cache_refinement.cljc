(ns eacl.formal.current-cache-refinement
  "Artifact-bound finite host refinement of the generated current-cache
  decision. The ten-entry domain is complete and deliberately data, not a
  second handwritten decision procedure.")

(def artifact-domain "eacl.current-cache-refinement.v1")

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

(def artifact-sha256
  "232a55f93dde5435fad517d0eeeb784afc988ebfc1bd1ec4bd1111b67175268b")

(def mapping-digest
  ;; This is release evidence, not request data. Computing it at namespace
  ;; initialization pulled the complete secure-envelope implementation into
  ;; the browser authorization kernel and repeated immutable work on every JVM
  ;; startup. The artifact-binding test recomputes this value independently.
  "xt0FF0JAAKNacKz5vyPmpmzLUOMZu3dOe4KG_-9UQzg")

(defn complete-mapping?
  [mapping]
  (= (set current-cache-domain) (set (keys mapping))))

(defn action
  [stage available?]
  (get current-cache-mapping [stage available?]))

(defn authorized-selection?
  [evidence]
  (and (complete-mapping? current-cache-mapping)
       (= artifact-sha256
          (:artifact-sha256 evidence))
       (= mapping-digest
          (:mapping-digest evidence))))
