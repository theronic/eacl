(ns eacl.formal.page-window-bridge
  (:import
   (dafny DafnySequence TypeDescriptor)
   (PageWindow
    ConsistencyMode
    ExactSelection
    Presence
    RawPageRequest)))

(defn- dafny-sequence
  [values]
  (DafnySequence/fromList
   TypeDescriptor/BIG_INTEGER
   (mapv biginteger values)))

(defn- presence
  [request key]
  (cond
    (not (contains? request key))
    (Presence/create_Absent TypeDescriptor/BIG_INTEGER)

    (nil? (get request key))
    (Presence/create_PresentNil TypeDescriptor/BIG_INTEGER)

    :else
    (Presence/create_PresentValue
     TypeDescriptor/BIG_INTEGER
     (biginteger (get request key)))))

(defn- raw-request
  [request]
  (RawPageRequest/create
   (presence request :first)
   (presence request :last)
   (presence request :after)
   (presence request :before)
   (contains? request :limit)
   (contains? request :cursor)))

(defn paginate
  [values request]
  (let [result
        (PageWindow.__default/PaginateRelationshipItems
         TypeDescriptor/BIG_INTEGER
         (dafny-sequence values)
         (raw-request request)
         (biginteger 1000)
         (biginteger 10000))
        normalized (.dtor__0 result)
        page (.dtor__1 result)]
    (if (.is_InvalidPageRequest normalized)
      {:status :invalid}
      {:status :valid
       :direction
       (if (.is_Ascending (.dtor_direction normalized))
         :asc
         :desc)
       :size (.longValue (.dtor_size normalized))
       :items (mapv #(.longValue %)
                    (.dtor_items page))
       :start (.longValue (.dtor_start page))
       :end (.longValue (.dtor_end page))
       :has-next? (.dtor_hasNext page)
       :has-previous? (.dtor_hasPrevious page)})))

(defn forward-walk
  [values size]
  (mapv
   #(.longValue %)
   (PageWindow.__default/ForwardWalk
    TypeDescriptor/BIG_INTEGER
    (dafny-sequence values)
    (biginteger size)
    (biginteger 0))))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString value))

(defn continuation-decision
  [{:keys [authenticated?
           scope-matches?
           expired?
           source
           cursor-source
           current-proof
           cursor-proof
           mode
           cursor-graph
           exact]}]
  (let [exact-selection
        (if exact
          (ExactSelection/create_ExactSnapshot
           (biginteger (:graph exact))
           (dafny-string (:source exact))
           (dafny-string (:proof exact)))
          (ExactSelection/create_ExactUnavailable))
        decision
        (PageWindow.__default/DecideContinuation
         (boolean authenticated?)
         (boolean scope-matches?)
         (boolean expired?)
         (dafny-string source)
         (dafny-string cursor-source)
         (dafny-string current-proof)
         (dafny-string cursor-proof)
         (if (= :at-least mode)
           (ConsistencyMode/create_AtLeastAsFresh)
           (ConsistencyMode/create_MinimizeLatency))
         (biginteger cursor-graph)
         exact-selection)]
    (cond
      (.is_UseCurrent decision) :current
      (.is_UseExact decision) :exact
      (.is_CursorConflict (.dtor_reason decision)) :conflict
      (.is_CursorExpired (.dtor_reason decision)) :expired
      (.is_HistoryDivergence (.dtor_reason decision)) :divergence
      (.is_SnapshotUnavailable (.dtor_reason decision))
      :snapshot-unavailable
      (.is_InvalidAuthentication (.dtor_reason decision))
      :invalid-authentication
      :else :scope-mismatch)))
