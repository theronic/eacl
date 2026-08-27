(ns eacl.formal.java-operator-recursive
  (:import
   (EaclKernel __default)
   (OperatorRecursiveGeneratedPolicy AnchorState ExclusionRule
                                     ExpressionStratum LowerStratumQuestion
                                     PositiveConsumerEdge PositiveRule
                                     RecursiveAction RecursiveCommand
                                     RecursiveCommandFailure RecursiveState
                                     RecursiveTransition TypedExpressionFact)
   (dafny DafnySequence TypeDescriptor)
   (java.math BigInteger)))

(defn- dafny-nat [value]
  (biginteger value))

(defn- nat->long [^BigInteger value]
  (.longValueExact value))

(defn- dafny-sequence [descriptor values]
  (DafnySequence/fromList descriptor values))

(defn- fact->dafny [{:keys [expression entity-type entity-eid]}]
  (TypedExpressionFact/create_TypedExpressionFact
   (dafny-nat expression) (dafny-nat entity-type) (dafny-nat entity-eid)))

(defn- fact->clj [^TypedExpressionFact fact]
  {:expression (nat->long (.dtor_expression fact))
   :entity-type (nat->long (.dtor_entityType fact))
   :entity-eid (nat->long (.dtor_entityEid fact))})

(defn- question->dafny
  [{:keys [parent-expression negative-expression entity-type entity-eid
           parent-stratum negative-stratum]}]
  (LowerStratumQuestion/create_LowerStratumQuestion
   (dafny-nat parent-expression)
   (dafny-nat negative-expression)
   (dafny-nat entity-type)
   (dafny-nat entity-eid)
   (dafny-nat parent-stratum)
   (dafny-nat negative-stratum)))

(defn- question->clj [^LowerStratumQuestion question]
  {:parent-expression (nat->long (.dtor_parentExpression question))
   :negative-expression (nat->long (.dtor_negativeExpression question))
   :entity-type (nat->long (.dtor_entityType question))
   :entity-eid (nat->long (.dtor_entityEid question))
   :parent-stratum (nat->long (.dtor_parentStratum question))
   :negative-stratum (nat->long (.dtor_negativeStratum question))})

(defn- anchor->clj [^AnchorState anchor]
  {:parent-expression (nat->long (.dtor_parentExpression anchor))
   :entity-type (nat->long (.dtor_entityType anchor))
   :entity-eid (nat->long (.dtor_entityEid anchor))
   :satisfied-slots (mapv boolean (.dtor_satisfiedSlots anchor))
   :satisfied-count (nat->long (.dtor_satisfiedCount anchor))})

(defn- action->clj [^RecursiveAction action]
  (cond
    (.is_ScheduleTypedFact action)
    {:kind :schedule-fact :fact (fact->clj (.dtor_fact action))}

    (.is_AskExactLowerStratum action)
    {:kind :ask-exact-lower
     :question (question->clj (.dtor_question action))}

    :else
    (throw (ex-info "Unknown generated recursive action." {:value action}))))

(defn- state->dafny
  [{:keys [facts completed-strata pending-lower-questions]}]
  (RecursiveState/create_RecursiveState
   (dafny-sequence (TypedExpressionFact/_typeDescriptor)
                   (mapv fact->dafny facts))
   (dafny-sequence (AnchorState/_typeDescriptor) [])
   (dafny-sequence TypeDescriptor/BIG_INTEGER
                   (mapv dafny-nat completed-strata))
   (dafny-sequence (LowerStratumQuestion/_typeDescriptor)
                   (mapv question->dafny pending-lower-questions))))

(defn- state->clj [^RecursiveState state]
  {:facts (mapv fact->clj (.dtor_facts state))
   :anchor-states (mapv anchor->clj (.dtor_anchorStates state))
   :completed-strata (mapv nat->long (.dtor_completedStrata state))
   :pending-lower-questions
   (mapv question->clj (.dtor_pendingLowerQuestions state))})

(defn- rule->dafny
  [{:keys [parent-expression width intersection? anchor-slot]}]
  (PositiveRule/create_PositiveRule
   (dafny-nat parent-expression) (dafny-nat width)
   (boolean intersection?) (dafny-nat anchor-slot)))

(defn- edge->dafny
  [{:keys [child-expression parent-expression slot]}]
  (PositiveConsumerEdge/create_PositiveConsumerEdge
   (dafny-nat child-expression) (dafny-nat parent-expression)
   (dafny-nat slot)))

(defn- stratum->dafny [[expression stratum]]
  (ExpressionStratum/create_ExpressionStratum
   (dafny-nat expression) (dafny-nat stratum)))

(defn- exclusion->dafny
  [{:keys [parent-expression left-expression negative-expression]}]
  (ExclusionRule/create_ExclusionRule
   (dafny-nat parent-expression) (dafny-nat left-expression)
   (dafny-nat negative-expression)))

(defn- command->dafny [{:keys [kind fact stratum question]}]
  (case kind
    :admit-fact
    (RecursiveCommand/create_AdmitTypedFact (fact->dafny fact))

    :complete-stratum
    (RecursiveCommand/create_CompleteStratum (dafny-nat stratum))

    :resolve-exact-lower
    (RecursiveCommand/create_ResolveExactLowerStratum
     (question->dafny question))

    (throw (ex-info "Unknown recursive command." {:kind kind}))))

(defn- failure->clj [^RecursiveCommandFailure failure]
  {:kind (cond
           (.is_IncompleteLowerStratum failure) :incomplete-lower-stratum
           (.is_InvalidLowerStratum failure) :invalid-lower-stratum
           :else :unknown)
   :question (question->clj (.dtor_question failure))})

(defn decide
  [{:keys [state positive-rules positive-edges strata exclusions command]}]
  (let [^RecursiveTransition transition
        (__default/DecideOperatorRecursiveCommand
         (state->dafny state)
         (dafny-sequence (PositiveRule/_typeDescriptor)
                         (mapv rule->dafny positive-rules))
         (dafny-sequence (PositiveConsumerEdge/_typeDescriptor)
                         (mapv edge->dafny positive-edges))
         (dafny-sequence (ExpressionStratum/_typeDescriptor)
                         (mapv stratum->dafny strata))
         (dafny-sequence (ExclusionRule/_typeDescriptor)
                         (mapv exclusion->dafny exclusions))
         (command->dafny command))]
    (if (.is_RecursiveTransitionAccepted transition)
      {:status :accepted
       :state (state->clj (.dtor_state transition))
       :actions (mapv action->clj (.dtor_actions transition))
       :duplicate-fact? (.dtor_duplicateFact transition)}
      {:status :rejected
       :failure (failure->clj (.dtor_failure transition))})))
