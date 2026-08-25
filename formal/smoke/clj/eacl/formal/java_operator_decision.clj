(ns eacl.formal.java-operator-decision
  (:import
   (EaclKernel OperatorDecision OperatorDependencyEdge OperatorEdgeSign
               OperatorSignedGraphDecision OperatorStrategy __default)
   (dafny DafnySequence TypeDescriptor)
   (java.math BigInteger)))

(defn- dafny-nat
  [value]
  (biginteger value))

(defn- dafny-booleans
  [values]
  (DafnySequence/fromList TypeDescriptor/BOOLEAN (mapv boolean values)))

(defn- dafny-nats
  [values]
  (DafnySequence/fromList TypeDescriptor/BIG_INTEGER
                          (mapv dafny-nat values)))

(defn- operator-sign
  [sign]
  (case sign
    :positive (OperatorEdgeSign/create_OperatorPositive)
    :negative (OperatorEdgeSign/create_OperatorNegative)
    (throw (ex-info "Unknown operator edge sign." {:sign sign}))))

(defn- operator-edge
  [{:keys [source target sign]}]
  (OperatorDependencyEdge/create_OperatorDependencyEdge
   (dafny-nat source)
   (dafny-nat target)
   (operator-sign sign)))

(defn- dafny-operator-edges
  [edges]
  (DafnySequence/fromList
   (OperatorDependencyEdge/_typeDescriptor)
   (mapv operator-edge edges)))

(defn- dafny-components
  [components]
  (DafnySequence/fromList
   (DafnySequence/_typeDescriptor TypeDescriptor/BIG_INTEGER)
   (mapv dafny-nats components)))

(defn- nat->long
  [^BigInteger value]
  (.longValueExact value))

(defn- strategy
  [^OperatorStrategy value]
  (cond
    (.is_OperatorEmpty value) :empty
    (.is_OperatorDensePrefix value) :dense-prefix
    (.is_OperatorSparseExact value) :sparse-exact
    :else (throw (ex-info "Unknown generated operator strategy."
                          {:value value}))))

(defn decide
  [{:keys [candidate-count first-eid last-eid maximum-span
           density-multiplier demand physical-cap candidate-window
           previous-width physical-decisions]}]
  (let [^OperatorDecision decision
        (__default/DecideOperatorBatch
         (dafny-nat candidate-count)
         (dafny-nat first-eid)
         (dafny-nat last-eid)
         (dafny-nat maximum-span)
         (dafny-nat density-multiplier)
         (dafny-nat demand)
         (dafny-nat physical-cap)
         (dafny-nat candidate-window)
         (dafny-nat previous-width)
         (dafny-booleans physical-decisions))]
    {:strategy (strategy (.dtor_strategy decision))
     :span-valid (.dtor_spanValid decision)
     :inclusive-span (nat->long (.dtor_inclusiveSpan decision))
     :initial-width (nat->long (.dtor_initialWidth decision))
     :grown-width (nat->long (.dtor_grownWidth decision))
     :logical-candidates (nat->long (.dtor_logicalCandidates decision))
     :physical-candidates (nat->long (.dtor_physicalCandidates decision))
     :physical-overread (nat->long (.dtor_physicalOverread decision))}))

(defn decide-signed-graph
  [{:keys [vertices edges components]}]
  (let [^OperatorSignedGraphDecision decision
        (__default/DecideOperatorSignedGraph
         (dafny-nats vertices)
         (dafny-operator-edges edges)
         (dafny-components components))]
    (cond
      (.is_OperatorSignedGraphAccepted decision)
      {:status :accepted}

      (.is_OperatorInvalidComponentCertificate decision)
      {:status :invalid-component-certificate}

      (.is_OperatorNonCanonicalEdgeSequence decision)
      {:status :noncanonical-edge-sequence}

      (.is_OperatorNegativeCycle decision)
      {:status :negative-cycle
       :edge-index (nat->long (.dtor_edgeIndex decision))
       :source (nat->long (.dtor_source decision))
       :target (nat->long (.dtor_target decision))}

      :else
      (throw (ex-info "Unknown generated signed-graph decision."
                      {:value decision})))))
