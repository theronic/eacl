(ns eacl.formal.operator-campaign
  "Operator-schema differential lane for the scheduled generated campaign.

  Every generator fixture carries `:operator-rules` (intersection and
  exclusion, including exclusion over the recursive folder component) next
  to its union-only `:rules`. This lane renders the combined schema to
  SpiceDB source, writes it into a production DataScript client, and
  compares every point decision for the operator permissions against the
  independent stratified finite-set oracle."
  (:require [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.operator-engine.oracle :as oracle]))

(defn- signatures
  "[resource-type relation] -> #{subject-type} over the fixture graph."
  [{:keys [relationships empty-relations]}]
  (reduce
   (fn [result [resource-type relation]]
     (update result [resource-type relation] #(or % #{:user})))
   (reduce
    (fn [result {:keys [subject relation resource]}]
      (update result
              [(:type resource) relation]
              (fnil conj #{})
              (:type subject)))
    {}
    relationships)
   empty-relations))

(defn- arrow-relation-targets
  "Relations referenced as arrow targets, per arrow `via` relation:
  {[resource-type via] #{relation}}."
  [rules]
  (reduce
   (fn [result [[resource-type _] expression]]
     (letfn [(walk [result node]
               (if-not (vector? node)
                 result
                 (case (first node)
                   :arrow
                   (let [[_ via [target-tag target-name]] node]
                     (if (= :relation target-tag)
                       (update result [resource-type via]
                               (fnil conj #{}) target-name)
                       result))
                   (:union :intersection :exclusion)
                   (reduce walk result (rest node))
                   result)))]
       (walk result expression)))
   {}
   rules))

(defn- via-permission [relation]
  (keyword (str (name relation) "_via")))

(defn- normalize-arrows
  "Rewrites arrow-to-relation targets through synthetic `<relation>_via`
  permissions so the rendered schema and the oracle share one arrow
  vocabulary."
  [expression]
  (if-not (vector? expression)
    expression
    (case (first expression)
      :arrow
      (let [[_ via [target-tag target-name]] expression]
        (if (= :relation target-tag)
          [:arrow via [:permission (via-permission target-name)]]
          expression))
      (:union :intersection :exclusion)
      (into [(first expression)]
            (map normalize-arrows)
            (rest expression))
      expression)))

(defn- synthetic-via-permissions
  "Permissions `[target-type <relation>_via] -> [:relation relation]` for
  every arrow-to-relation target, over every type the via relation can
  reach."
  [rules relation-signatures]
  (into
   {}
   (for [[[resource-type via] targets] (arrow-relation-targets rules)
         target-type (get relation-signatures [resource-type via] #{})
         target targets]
     [[target-type (via-permission target)] [:relation target]])))

(defn- combined-permissions
  [{:keys [rules operator-rules] :as fixture} relation-signatures]
  (let [base (merge rules operator-rules)]
    (merge
     (into {}
           (map (fn [[head expression]]
                  [head (normalize-arrows expression)]))
           base)
     (synthetic-via-permissions base relation-signatures))))

(defn- expression->source
  [expression]
  (if-not (vector? expression)
    (throw (ex-info "Malformed operator campaign expression."
                    {:expression expression}))
    (case (first expression)
      :relation (name (second expression))
      :permission (name (second expression))
      :arrow (let [[_ via [_ target]] expression]
               (str (name via) "->" (name target)))
      :union
      (str "(" (str/join " + " (map expression->source (rest expression)))
           ")")
      :intersection
      (str "(" (str/join " & " (map expression->source (rest expression)))
           ")")
      :exclusion
      (let [[_ left right] expression]
        (str "(" (expression->source left) " - "
             (expression->source right) ")"))
      (throw (ex-info "Unknown operator campaign expression tag."
                      {:expression expression})))))

(defn- schema-source
  [{:keys [objects] :as fixture} relation-signatures permissions]
  (let [types (into (sorted-set)
                    (concat (map :type objects)
                            (map first (keys relation-signatures))
                            (map first (keys permissions))))
        relations-by-type (group-by (comp first key)
                                    relation-signatures)
        permissions-by-type (group-by (comp first key) permissions)]
    (str/join
     "\n"
     (for [type types]
       (str "definition " (name type) " {\n"
            (str/join
             ""
             (for [[[_ relation] subject-types]
                   (sort-by key (relations-by-type type))]
               (str "  relation " (name relation) ": "
                    (str/join " | " (sort (map name subject-types)))
                    "\n")))
            (str/join
             ""
             (for [[[_ permission] expression]
                   (sort-by key (permissions-by-type type))]
               (str "  permission " (name permission) " = "
                    (expression->source expression) "\n")))
            "}\n")))))

(defn- oracle-snapshot
  [{:keys [objects relationships] :as fixture} relation-signatures
   permissions]
  {:objects (into #{} (map (juxt :type :id)) objects)
   :relationships
   (into #{}
         (map (fn [{:keys [subject relation resource]}]
                {:subject [(:type subject) (:id subject)]
                 :relation relation
                 :resource [(:type resource) (:id resource)]}))
         relationships)
   :relation-target-types relation-signatures
   :permissions
   (into {}
         (map (fn [[head expression]]
                (letfn [(convert [node]
                          (case (first node)
                            :relation node
                            :permission node
                            :arrow (let [[_ via [_ target]] node]
                                     [:arrow via target])
                            (:union :intersection :exclusion)
                            (into [(first node)]
                                  (map convert)
                                  (rest node))))]
                  [head (convert expression)])))
         permissions)})

(defn- fixture-client
  [fixture schema]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (binding [orchestration/*operator-expression-writes-enabled?* true]
      (eacl/write-schema! client schema))
    (ds/transact! conn (mapv #(hash-map :eacl/id (:id %))
                             (:objects fixture)))
    (eacl/create-relationships!
     client
     (mapv (fn [{:keys [subject relation resource]}]
             (eacl/->Relationship
              (eacl/spice-object (:type subject) (:id subject))
              relation
              (eacl/spice-object (:type resource) (:id resource))))
           (:relationships fixture)))
    client))

(defn mismatch
  "Runs the operator differential for one fixture. Returns nil when every
  production point decision over the operator permissions equals the
  stratified oracle, or a reproducible difference report."
  [fixture]
  (try
    (let [relation-signatures (signatures fixture)
          permissions (combined-permissions fixture relation-signatures)
          schema (schema-source fixture relation-signatures permissions)
          snapshot (oracle-snapshot fixture relation-signatures permissions)
          evaluation (oracle/evaluate-stratified snapshot)
          client (fixture-client fixture schema)
          subject-candidates
          (filterv #(contains? #{:user :group} (:type %))
                   (:objects fixture))
          differences
          (binding [engine/*operator-routing-enabled?* true]
            (vec
             (for [[[resource-type permission] _] (:operator-rules fixture)
                   resource (filter #(= resource-type (:type %))
                                    (:objects fixture))
                   subject subject-candidates
                   :let [production
                         (eacl/can?
                          client
                          {:subject (eacl/spice-object (:type subject)
                                                       (:id subject))
                           :permission permission
                           :resource (eacl/spice-object (:type resource)
                                                        (:id resource))})
                         expected
                         (oracle/evaluated-check?
                          evaluation
                          [(:type subject) (:id subject)]
                          permission
                          [(:type resource) (:id resource)])]
                   :when (not= (boolean expected) (boolean production))]
               {:subject [(:type subject) (:id subject)]
                :permission permission
                :resource [(:type resource) (:id resource)]
                :production (boolean production)
                :oracle (boolean expected)})))]
      (when (seq differences)
        {:operator-differential
         {:status :failed
          :seed (:seed fixture)
          :differences differences}}))
    (catch Throwable error
      {:operator-exception {:class (str (class error))
                            :message (ex-message error)
                            :data (ex-data error)}})))
