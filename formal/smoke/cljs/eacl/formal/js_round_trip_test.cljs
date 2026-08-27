(ns eacl.formal.js-round-trip-test
  (:require
   [cljs.reader :as reader]
   [cljs.test :refer-macros [deftest is]]))

(def generated
  (js/require
   (.resolve
    (js/require "path")
    (.cwd js/process)
    "formal/smoke/js/generated_loader.cjs")))

(defn- cross-runtime-vectors
  []
  (-> (js/require "fs")
      (.readFileSync "formal/cross-runtime/vectors.edn" "utf8")
      reader/read-string))

(defn- dafny-string
  [value]
  (.UnicodeFromString
   (.-Seq (.-_dafny generated))
   value))

(defn- big-number
  [value]
  (new (.-BigNumber generated) value))

(defn- dafny-sequence
  [values]
  (.apply
   (.-of (.-Seq (.-_dafny generated)))
   (.-Seq (.-_dafny generated))
   (into-array values)))

(defn- round-trip
  [tag values limit]
  (js-invoke
   (.-__default (.-EaclKernel generated))
   "RoundTrip"
   (dafny-string tag)
   (into-array (map big-number values))
   (big-number limit)))

(deftest generated-javascript-value-collection-and-error-round-trip
  (let [accepted (round-trip "eacl.round-trip/v1" [0 7 42] 3)]
    (is (true? (.-is_Accepted accepted)))
    (is (= [0 7 42]
           (mapv #(.toNumber %) (.-dtor_items accepted)))))
  (is (true? (.-is_Rejected (round-trip "unknown" [1] 1))))
  (is (true?
       (.-is_Rejected
        (round-trip "eacl.round-trip/v1" [1 -1] 2))))
  (is (true?
       (.-is_Rejected
       (round-trip "eacl.round-trip/v1" [1 2] 1)))))

(defn- object-ref
  [type-name object-id]
  (js-invoke
   (.-ObjectRef (.-Semantics generated))
   "create_ObjectRef"
   (dafny-string type-name)
   (dafny-string object-id)))

(defn- permission-node
  [resource-type permission-name]
  (js-invoke
   (.-PermissionNode (.-Semantics generated))
   "create_PermissionNode"
   (dafny-string resource-type)
   (dafny-string permission-name)))

(defn- relationship
  [resource relation-name subject]
  (js-invoke
   (.-Relationship (.-Semantics generated))
   "create_Relationship"
   resource
   (dafny-string relation-name)
   subject))

(defn- direct-definition
  [node relation-name subject-type]
  (js-invoke
   (.-RuleDefinition (.-Semantics generated))
   "create_DirectRelation"
   node
   (dafny-string relation-name)
   (dafny-string subject-type)))

(defn- arrow-permission-definition
  [node via-relation target-permission]
  (js-invoke
   (.-RuleDefinition (.-Semantics generated))
   "create_ArrowPermission"
   node
   (dafny-string via-relation)
   (dafny-string target-permission)))

(defn- object-id
  [object]
  (.toVerbatimString (.-dtor_objectId object) false))

(defn- wire-query
  [offset]
  (let [semantics (.-Semantics generated)
        wire (.-WireFormat generated)]
    (js-invoke
     (.-WireQuery wire)
     "create_WireQuery"
     (js-invoke
      (.-Query semantics)
      "create_Query"
      (object-ref "user" "u1")
      (permission-node "document" "view")
      (object-ref "document" "d1"))
     (big-number offset)
     (big-number 25))))

(defn- wire-limits
  [max-objects]
  (js-invoke
   (.-WireLimits (.-WireFormat generated))
   "create_WireLimits"
   (big-number 16)
   (big-number max-objects)
   (big-number 16)
   (big-number 16)
   (big-number 16)
   (big-number 32)
   (big-number 64)
   (big-number 100)))

(defn- generated-wire-decode
  [scenario]
  (let [wire (.-WireFormat generated)
        fields-type (.-RequestField wire)
        objects
        (if (= :invalid-identity scenario)
          [(object-ref "" "u1")
           (object-ref "document" "d1")]
          [(object-ref "user" "u1")
           (object-ref "document" "d1")])
        offset
        (case scenario
          :negative-offset -1
          :unsafe-offset 9007199254740992
          0)
        base
        [(js-invoke
          fields-type "create_VersionField"
          (dafny-string "eacl.engine/v1"))
         (js-invoke
          fields-type "create_ObjectsField"
          (dafny-sequence objects))
         (js-invoke
          fields-type "create_RelationsField"
          (dafny-sequence []))
         (js-invoke
          fields-type "create_PermissionsField"
          (dafny-sequence []))
         (js-invoke
          fields-type "create_DefinitionsField"
          (dafny-sequence []))
         (js-invoke
          fields-type "create_RelationshipsField"
          (dafny-sequence []))
         (js-invoke
          fields-type "create_QueryField"
          (wire-query offset))]
        fields
        (case scenario
          :duplicate-field (conj base (first base))
          :unknown-field
          (assoc base 1
                 (js-invoke
                  fields-type "create_UnknownField"
                  (dafny-string "forged")))
          base)
        result
        (js-invoke
         (.-__default wire)
         "DecodeRequest"
         (dafny-sequence fields)
         (wire-limits
          (if (= :oversized-collection scenario) 1 16)))]
    (if (.-is_Decoded result)
      {:status :decoded}
      (let [error (.-dtor_error result)]
        {:status :rejected
         :reason
         (cond
           (.-is_DuplicateField error) :duplicate-field
           (.-is_UnknownFieldName error) :unknown-field
           (.-is_OversizedCollection error)
           :oversized-collection
           (.-is_InvalidIdentity error) :invalid-identity
           :else :invalid-range)}))))

(deftest generated-javascript-strict-wire-boundary
  (is (= {:status :decoded}
         (generated-wire-decode :valid)))
  (doseq [[scenario reason]
          [[:duplicate-field :duplicate-field]
           [:unknown-field :unknown-field]
           [:oversized-collection :oversized-collection]
           [:negative-offset :invalid-range]
           [:unsafe-offset :invalid-range]
           [:invalid-identity :invalid-identity]]]
    (is (= {:status :rejected :reason reason}
           (generated-wire-decode scenario))
        (name scenario))))

(deftest generated-javascript-direct-and-acyclic-kernel
  (let [user-1 (object-ref "user" "user-1")
        user-2 (object-ref "user" "user-2")
        document-1 (object-ref "document" "document-1")
        document-2 (object-ref "document" "document-2")
        node (permission-node "document" "view")
        definitions
        (dafny-sequence
         [(direct-definition node "reader" "user")])
        objects
        (dafny-sequence
         [user-1 user-2 document-1 document-2])
        permissions (dafny-sequence [node])
        relationships
        (dafny-sequence
         [(relationship document-1 "reader" user-1)])
        kernel (.-__default (.-AcyclicEngine generated))
        query-1
        (js-invoke
         (.-Query (.-Semantics generated))
         "create_Query"
         user-1 node document-1)
        query-2
        (js-invoke
         (.-Query (.-Semantics generated))
         "create_Query"
         user-2 node document-1)]
    (is (= 1
           (.-length
            (js-invoke kernel "CompilePaths" definitions))))
    (is (true?
         (js-invoke
          kernel "DirectCan"
          objects permissions definitions relationships query-1)))
    (is (false?
         (js-invoke
          kernel "DirectCan"
          objects permissions definitions relationships query-2)))
    (is (= ["document-1"]
           (mapv
            object-id
            (js-invoke
             kernel "AcyclicForward"
             objects permissions definitions relationships user-1 node))))
    (is (= ["user-1"]
           (mapv
            object-id
            (js-invoke
             kernel "AcyclicReverse"
             objects permissions definitions relationships document-1 node))))
    (let [count-result
          (js-invoke
           kernel "CountForward"
           objects permissions definitions relationships
           user-1 node (big-number 0))]
      (is (= 0 (.toNumber (aget count-result 0))))
      (is (true? (aget count-result 1))))))

(deftest generated-javascript-recursive-worklist-and-limit
  (let [user (object-ref "user" "recursive-user")
        folder-0 (object-ref "folder" "folder-0")
        folder-1 (object-ref "folder" "folder-1")
        node (permission-node "folder" "selfread")
        objects (dafny-sequence [user folder-0 folder-1])
        permissions (dafny-sequence [node])
        definitions
        (dafny-sequence
         [(direct-definition node "reader" "user")
          (arrow-permission-definition
           node "parent" "selfread")])
        relationships
        (dafny-sequence
         [(relationship folder-0 "reader" user)
          (relationship folder-1 "parent" folder-0)])
        recursive (.-RecursiveEngine generated)
        kernel (.-__default recursive)
        generous
        (js-invoke
         (.-TraversalLimits recursive)
         "create_TraversalLimits"
         (big-number 100)
         (big-number 100)
         (big-number 100))
        no-derived
        (js-invoke
         (.-TraversalLimits recursive)
         "create_TraversalLimits"
         (big-number 0)
         (big-number 100)
         (big-number 100))
        forward
        (js-invoke
         kernel
         "RecursiveForward"
         objects permissions definitions relationships
         user node generous)
        reverse
        (js-invoke
         kernel
         "RecursiveReverse"
         objects permissions definitions relationships
         folder-1 node generous)
        limited
        (js-invoke
         kernel
         "RecursiveForward"
         objects permissions definitions relationships
         user node no-derived)]
    (is (true? (.-is_SequenceComplete forward)))
    (is (= ["folder-0" "folder-1"]
           (mapv object-id (.-dtor_items forward))))
    (is (true? (.-is_SequenceComplete reverse)))
    (is (= ["recursive-user"]
           (mapv object-id (.-dtor_items reverse))))
    (is (true? (.-is_SequenceLimitExceeded limited)))
    (is (true? (.-is_DerivedGrants (.-dtor_kind limited))))))

(defn- page-presence
  [request key]
  (let [presence (.-Presence (.-PageWindow generated))]
    (cond
      (not (contains? request key))
      (js-invoke presence "create_Absent")

      (nil? (get request key))
      (js-invoke presence "create_PresentNil")

      :else
      (js-invoke
       presence
       "create_PresentValue"
       (big-number (get request key))))))

(defn- generated-page
  [values request]
  (let [page-window (.-PageWindow generated)
        raw
        (js-invoke
         (.-RawPageRequest page-window)
         "create_RawPageRequest"
         (page-presence request :first)
         (page-presence request :last)
         (page-presence request :after)
         (page-presence request :before))
        result
        (js-invoke
         (.-__default page-window)
         "PaginateRelationshipItems"
         (dafny-sequence (map big-number values))
         raw
         (big-number 1000)
         (big-number 10000))
        normalized (aget result 0)
        page (aget result 1)]
    (if (.-is_InvalidPageRequest normalized)
      {:status :invalid}
      {:status :valid
       :items (mapv #(.toNumber %) (.-dtor_items page))
       :start (.toNumber (.-dtor_start page))
       :end (.toNumber (.-dtor_end page))
       :has-next? (.-dtor_hasNext page)
       :has-previous? (.-dtor_hasPrevious page)})))

(defn- expected-page
  [values request]
  (let [n (count values)
        asc? (contains? request :first)
        size (or (:first request) (:last request) 1000)
        bound (if asc? (:after request) (:before request))
        end (if asc?
              nil
              (if (some? bound) (min n bound) n))
        start (if asc?
                (if (some? bound) (min n (inc bound)) 0)
                (max 0 (- end size)))
        end (if asc? (min n (+ start size)) end)]
    {:status :valid
     :items (subvec values start end)
     :start start
     :end end
     :has-next? (and (< start end) (< end n))
     :has-previous? (and (< start end) (pos? start))}))

(deftest generated-javascript-page-window-parity
  (doseq [request
          [{:first 1 :last 1}
           {:first 1 :after nil}
           {:last 1 :before nil}
           {:first 0}
           {:first 10001}]]
    (is (= :invalid (:status (generated-page [0 1 2] request)))))
  (doseq [n (range 11)
          size (range 1 5)
          direction [:asc :desc]
          bound (range (inc n))]
    (let [values (vec (range n))
          request
          (if (= :asc direction)
            {:first size :after bound}
            {:last size :before bound})]
      (is (= (expected-page values request)
             (generated-page values request))
          (pr-str {:n n :request request})))))

(defn- generated-continuation-decision
  [{:keys [current-proof cursor-proof exact expired?]
    :or {current-proof "proof"
         cursor-proof "proof"
         expired? false}}]
  (let [page-window (.-PageWindow generated)
        exact-selection
        (if exact
          (js-invoke
           (.-ExactSelection page-window)
           "create_ExactSnapshot"
           (big-number (:graph exact))
           (dafny-string (:source exact))
           (dafny-string (:proof exact)))
          (js-invoke
           (.-ExactSelection page-window)
           "create_ExactUnavailable"))
        ]
    (js-invoke
     (.-__default page-window)
     "DecideContinuation"
     true
     true
     expired?
     (dafny-string "source")
     (dafny-string "source")
     (dafny-string current-proof)
     (dafny-string cursor-proof)
     (big-number 7)
     exact-selection)))

(deftest generated-javascript-cursor-decision-parity
  (is (true?
       (.-is_UseCurrent
        (generated-continuation-decision {}))))
  (is (true?
       (.-is_UseExact
        (generated-continuation-decision
         {:current-proof "changed"
          :exact {:graph 7
                  :source "source"
                  :proof "proof"}}))))
  (is (true?
       (.-is_SnapshotUnavailable
        (.-dtor_reason
         (generated-continuation-decision
          {:current-proof "changed"})))))
  (is (true?
       (.-is_CursorExpired
        (.-dtor_reason
         (generated-continuation-decision
          {:expired? true})))))
  (is (true?
       (.-is_HistoryDivergence
        (.-dtor_reason
        (generated-continuation-decision
          {:current-proof "changed"
           :exact {:graph 8
                   :source "source"
                   :proof "proof"}}))))))

(defn- continuation-status
  [decision]
  (cond
    (.-is_UseCurrent decision) :current
    (.-is_UseExact decision) :exact
    (.-is_CursorExpired (.-dtor_reason decision)) :expired
    (.-is_HistoryDivergence (.-dtor_reason decision)) :divergence
    (.-is_SnapshotUnavailable (.-dtor_reason decision))
    :snapshot-unavailable
    (.-is_InvalidAuthentication (.-dtor_reason decision))
    :invalid-authentication
    :else :scope-mismatch))

(deftest generated-javascript-cross-runtime-vectors
  (let [{:keys [graph pages continuations round-trips]}
        (cross-runtime-vectors)
        {:keys [fixture subject resource permission expected]} graph
        generated-objects
        (into
         {}
         (map
          (fn [{:keys [type id] :as value}]
            [value (object-ref (name type) id)]))
         (:objects fixture))
        node (permission-node (name (:type resource)) (name permission))
        relation-name
        (name
         (second
          (get-in fixture [:rules [(:type resource) permission]])))
        definitions
        (dafny-sequence
         [(direct-definition node relation-name (name (:type subject)))])
        objects (dafny-sequence (vals generated-objects))
        permissions (dafny-sequence [node])
        relationships
        (dafny-sequence
         (map
          (fn [{:keys [subject relation resource]}]
            (relationship
             (get generated-objects resource)
             (name relation)
             (get generated-objects subject)))
          (:relationships fixture)))
        kernel (.-__default (.-AcyclicEngine generated))]
    (is (true?
         (js-invoke
          kernel "DirectCan"
          objects permissions definitions relationships
          (js-invoke
           (.-Query (.-Semantics generated))
           "create_Query"
           (get generated-objects subject)
           node
           (get generated-objects resource)))))
    (is (= (:forward expected)
           (mapv
            object-id
            (js-invoke
             kernel "AcyclicForward"
             objects permissions definitions relationships
             (get generated-objects subject) node))))
    (is (= (:reverse expected)
           (mapv
            object-id
            (js-invoke
             kernel "AcyclicReverse"
             objects permissions definitions relationships
             (get generated-objects resource) node))))
    (doseq [{:keys [values request expected]} pages]
      (is (= expected
             (select-keys
              (generated-page values request)
              (keys expected)))
          (pr-str request)))
    (doseq [{:keys [input expected]} continuations]
      (is (= expected
             (continuation-status
              (generated-continuation-decision input)))
          (pr-str input)))
    (doseq [{:keys [tag values limit expected]} round-trips]
      (let [result (round-trip tag values limit)
            actual
            (if (.-is_Accepted result)
              {:status :accepted
               :values (mapv #(.toNumber %) (.-dtor_items result))}
              {:status :rejected})]
        (is (= expected actual)
            (pr-str [tag values limit]))))))
