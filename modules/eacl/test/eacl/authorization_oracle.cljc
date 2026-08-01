(ns eacl.authorization-oracle
  "A deliberately small authorization reference evaluator used only by the
  cross-backend contract tests.

  This implementation does not parse EACL schemas, call the production
  engine, use backend indexes, or share traversal helpers with an adapter. It
  evaluates an inspectable rule map to a least fixed point over an in-memory
  relationship set."
  (:require [clojure.set :as set]))

(def fixture-seed
  "Stable identifier printed with differential failures. The fixtures are
  curated rather than randomly generated, so this seed names their exact
  reproducible revision."
  820084)

(def smoke-rules
  {[:account :admin]
   [:union
    [:relation :owner]
    [:arrow :platform [:relation :super_admin]]]

   [:account :view]
   [:permission :admin]

   [:server :view]
   [:arrow :account [:permission :view]]

   [:server :reboot]
   [:arrow :account [:permission :admin]]})

(def recursive-rules
  {[:folder :selfread]
   [:union
    [:relation :reader]
    [:arrow :parent [:permission :selfread]]]

   [:folder :read]
   [:union
    [:relation :reader]
    [:relation :editor]
    [:arrow :parent [:permission :write]]]

   [:folder :write]
   [:permission :read]

   [:folder :duplicate]
   [:union
    [:permission :read]
    [:relation :reader]
    [:arrow :parent [:permission :read]]]})

(defn- direct-subjects
  [relationships resource relation]
  (into #{}
        (comp
         (filter #(and (= resource (:resource %))
                       (= relation (:relation %))))
         (map :subject))
        relationships))

(declare evaluate-node)

(defn- evaluate-arrow
  [relationships grants resource relation target]
  (reduce
   (fn [subjects relationship]
     (if (and (= resource (:resource relationship))
              (= relation (:relation relationship)))
       (set/union
        subjects
        (evaluate-node relationships grants (:subject relationship) target))
       subjects))
   #{}
   relationships))

(defn- evaluate-node
  [relationships grants resource [operator & operands :as node]]
  (case operator
    :relation
    (direct-subjects relationships resource (first operands))

    :permission
    (get grants [resource (first operands)] #{})

    :union
    (reduce
     set/union
     #{}
     (map #(evaluate-node relationships grants resource %) operands))

    :arrow
    (evaluate-arrow
     relationships grants resource (first operands) (second operands))

    (throw
     (ex-info
      "Unknown reference-oracle rule node"
      {:node node
       :seed fixture-seed}))))

(defn authorization-set
  "Returns every `[subject permission resource]` tuple derived by `rules`.

  Rules are keyed by `[resource-type permission]`. Values use the tiny AST
  documented by `smoke-rules` and `recursive-rules`. The monotone iteration
  bound is derived from the finite fixture, making accidental non-convergence
  an inspectable test failure."
  [{:keys [objects relationships rules] :as fixture}]
  (let [rule-instances
        (for [[[resource-type permission] rule] rules
              resource objects
              :when (= resource-type (:type resource))]
          [resource permission rule])
        subjects (into (set objects) (map :subject) relationships)
        max-grants (* (max 1 (count subjects))
                      (max 1 (count rule-instances)))]
    (loop [grants {}
           iteration 0]
      (let [next-grants
            (reduce
             (fn [result [resource permission rule]]
               (update
                result
                [resource permission]
                (fnil set/union #{})
                (evaluate-node relationships grants resource rule)))
             grants
             rule-instances)]
        (cond
          (= grants next-grants)
          (into
           #{}
           (mapcat
            (fn [[[resource permission] authorized-subjects]]
              (map #(vector % permission resource) authorized-subjects)))
           next-grants)

          (> iteration max-grants)
          (throw
           (ex-info
            "Reference authorization oracle did not converge"
            {:seed fixture-seed
             :iteration iteration
             :fixture fixture}))

          :else
          (recur next-grants (inc iteration)))))))
