(ns eacl.datascript.wildcard-spicedb-golden-test
  "Differential test of wildcard subjects against SpiceDB v1.56.0.

  formal/fixtures/wildcards records SpiceDB's answers to every request in
  requests.json, for schema.zed and relationships.txt (see its README). This
  test writes the same schema and relationships to EACL on DataScript, sends
  the same requests and compares the answers:

  - checks and resource lookups by permissionship, and missing context;
  - permission trees by topology, with union and intersection children and
    leaf subjects unordered;
  - subject lookups by denotation: the permissionship every known subject of
    the type gets from its own entry or, unless `*` excludes it, the `*`
    entry. SpiceDB leaves out a granted subject that the wildcard already
    covers, EACL lists it (a superset with the same meaning), so the concrete
    subjects SpiceDB lists must be a subset of EACL's;
  - relationship reads by their relationships;
  - rejected requests by error class."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.test-support.repo :as repo]))

(defn- fixture
  [name]
  (slurp (repo/file "formal" "fixtures" "wildcards" name)))

(def ^:private relationship-pattern
  #"^([^:]+):([^#]+)#([^@]+)@([^:]+):([^\[]+)(?:\[([^\]]+)\])?$")

(defn- parse-relationship
  [text]
  (let [[_ resource-type resource-id relation subject-type subject-id caveat]
        (re-matches relationship-pattern text)]
    (assert resource-type (str "unparseable relationship: " text))
    (cond-> (eacl/->Relationship (eacl/spice-object (keyword subject-type) subject-id)
                                 (keyword relation)
                                 (eacl/spice-object (keyword resource-type) resource-id))
      caveat (assoc :caveat caveat))))

(defn- render-relationship
  [{:keys [subject relation resource caveat]}]
  (str (name (:type resource)) ":" (:id resource) "#" (name relation)
       "@" (name (:type subject)) ":" (:id subject)
       (when caveat (str "[" caveat "]"))))

(defn- ->object [[type id]] (eacl/spice-object (keyword type) id))

(defn- tree-node
  "An EACL permission-tree node in the fixture's form."
  [{:keys [expanded-object expanded-relation leaf intermediate]}]
  (let [node {:object [(name (:type expanded-object)) (:id expanded-object)]
              :relation (name expanded-relation)}]
    (if leaf
      (assoc node :subjects
             (mapv (fn [{:keys [type id relation]}]
                     (cond-> [(name type) id] relation (conj (name relation))))
                   (:subjects leaf)))
      (assoc node
             :operation (:operation intermediate)
             :children (mapv tree-node (:children intermediate))))))

(defn- canonical-tree
  "Orders leaf subjects and union and intersection children; an exclusion
  keeps its base first."
  [node]
  (if (contains? node :subjects)
    (update node :subjects #(vec (sort-by pr-str %)))
    (update node :children
            (fn [children]
              (let [children (mapv canonical-tree children)]
                (if (= :exclusion (:operation node))
                  children
                  (vec (sort-by pr-str children))))))))

(def ^:private unmentioned
  "An existing subject of each type that no relationship names: it holds
  exactly what the wildcard holds."
  {"user" "zz-user" "team" "zz-team"})

(defn- universe
  "Every subject ID of `type` the fixture knows."
  [relationships cases type]
  (-> #{(unmentioned type)}
      (into (keep (fn [{:keys [subject]}]
                    (when (= (keyword type) (:type subject)) (:id subject))))
            relationships)
      (into (keep (fn [{{:keys [subject]} :request}]
                    (when (= type (first subject)) (second subject))))
            cases)
      (disj "*")))

(defn- walk
  [f query]
  (loop [query query items [] guard 0]
    (let [page (f query)
          items (into items (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< guard 100))
        (recur (assoc query :after (get-in page [:page-info :end-cursor])) items (inc guard))
        items))))

(def ^:private error-classes
  "The EACL error each SpiceDB rejection corresponds to. SpiceDB reports a
  bare `user:*` on a relation that allows only `user:* with c` as an invalid
  subject type; EACL's qualified writer reports the missing Caveat, as it does
  for concrete subjects."
  {"ERROR_REASON_WILDCARD_NOT_ALLOWED" #{:eacl/wildcard-not-allowed}
   "ERROR_REASON_INVALID_SUBJECT_TYPE" #{:eacl/unknown-relation-or-permission
                                         :eacl.qualifier/staged-write}
   "ERROR_REASON_SCHEMA_TYPE_ERROR" #{:eacl.schema/expression-resolution-failed}
   ;; SpiceDB rejects `*` as a resource ID in request validation, without an
   ;; error reason.
   nil #{:eacl/wildcard-not-allowed}})

(defn- run-request
  [client {:keys [op subject resource permission resource-type subject-type
                  subject-id relationship schema context]}]
  (try
    (case op
      :check
      (let [result (eacl/check-permission
                    client (cond-> {:subject (->object subject)
                                    :permission (keyword permission)
                                    :resource (->object resource)}
                             context (assoc :caveat-context context)))]
        (cond-> {:permissionship (:permissionship result)}
          (seq (:missing-fields result))
          (assoc :missing-fields (vec (sort (:missing-fields result))))))

      :lookup-resources
      {:resources
       (into {}
             (map (fn [{:keys [object permissionship]}] [(:id object) permissionship]))
             (walk #(eacl/lookup-resources client %)
                   (cond-> {:subject (->object subject)
                            :permission (keyword permission)
                            :resource/type (keyword resource-type)
                            :result-policy :detailed
                            :first 2}
                     context (assoc :caveat-context context))))}

      :lookup-subjects
      (let [query (cond-> {:resource (->object resource)
                           :permission (keyword permission)
                           :subject/type (keyword subject-type)
                           :first 2}
                    context (assoc :caveat-context context))]
        {:detailed (walk #(eacl/lookup-subjects client %)
                         (assoc query :result-policy :detailed))
         :definite (walk #(eacl/lookup-subjects client %) query)})

      :expand
      {:tree (tree-node (:tree-root (eacl/expand-permission-tree
                                     client {:resource (->object resource)
                                             :permission (keyword permission)})))}

      :read
      {:relationships
       (vec (sort (map render-relationship
                       (:data (eacl/read-relationships
                               client
                               (cond-> {:resource/type (keyword resource-type)}
                                 subject-type (assoc :subject/type (keyword subject-type))
                                 subject-id (assoc :subject/id subject-id)))))))}

      :write
      (do (eacl/write-relationships!
           client [{:operation :touch :relationship (parse-relationship relationship)}])
          {:written true})

      :write-schema
      (do (eacl/write-schema! client schema)
          {:written true}))
    (catch clojure.lang.ExceptionInfo error
      {:error (:type (ex-data error))})))

(defn- either
  "The permissionship of a grant by either of two entries."
  [a b]
  (cond
    (some #{:has-permission} [a b]) :has-permission
    (some #{:conditional-permission} [a b]) :conditional-permission
    :else :no-permission))

(defn- spicedb-status
  "The permissionship SpiceDB's subject entries give `id`: its own entry, or
  the `*` entry unless `*` excludes it."
  [subjects id]
  (let [by-id (into {} (map (juxt :id identity)) subjects)
        wildcard (by-id "*")
        exclusion (some #(when (= id (:id %)) %) (:excluded wildcard))]
    (either
     (get-in by-id [id :permissionship] :no-permission)
     (cond
       (nil? wildcard) :no-permission
       (nil? exclusion) (:permissionship wildcard)
       (= :has-permission (:permissionship exclusion)) :no-permission
       :else :conditional-permission))))

(defn- eacl-status
  "The permissionship EACL's detailed subject entries give `id`, read the same
  way."
  [items id]
  (let [by-id (into {} (map (fn [{:keys [object] :as item}] [(:id object) item])) items)
        wildcard (by-id "*")
        excluded (set (map :id (get-in wildcard [:object :excluded-subjects])))]
    (either
     (get-in by-id [id :permissionship] :no-permission)
     (if (and wildcard (not (excluded id)))
       (:permissionship wildcard)
       :no-permission))))

(defn- definitely-granted
  "The subjects of `universe` EACL's definite (default) listing grants."
  [objects universe]
  (let [by-id (into {} (map (juxt :id identity)) objects)]
    (set (filter (fn [id]
                   (or (contains? by-id id)
                       (and (by-id "*")
                            (not (contains? (set (map :id (:excluded-subjects (by-id "*"))))
                                            id)))))
                 universe))))

(defn- assert-case!
  [client relationships cases {:keys [id request spicedb]}]
  (testing id
    (let [actual (run-request client request)]
      (cond
        (:error spicedb)
        (is (contains? (error-classes (get-in spicedb [:error :reason])) (:error actual))
            (pr-str actual))

        (= :lookup-subjects (:op request))
        (let [type (:subject-type request)
              universe (universe relationships cases type)
              {:keys [detailed definite]} actual
              listed (set (map (comp :id :object) detailed))
              spicedb-listed (set (map :id (:subjects spicedb)))]
          (is (nil? (:error actual)) (pr-str actual))
          (is (= (into {} (map (fn [u] [u (spicedb-status (:subjects spicedb) u)])) universe)
                 (into {} (map (fn [u] [u (eacl-status detailed u)])) universe))
              "every known subject gets SpiceDB's permissionship")
          (is (= (contains? spicedb-listed "*") (contains? listed "*"))
              "the wildcard entry is returned exactly when SpiceDB returns it")
          (is (set/subset? spicedb-listed listed)
              "EACL lists every subject SpiceDB lists")
          (is (= (set (filter #(= :has-permission (spicedb-status (:subjects spicedb) %)) universe))
                 (definitely-granted definite universe))
              "the default listing grants SpiceDB's definite subjects"))

        (= :expand (:op request))
        (is (= (canonical-tree (:tree spicedb)) (canonical-tree (:tree actual))))

        :else
        (is (= spicedb actual))))))

(deftest wildcard-answers-match-spicedb
  (let [relationships (mapv parse-relationship
                            (remove #(re-matches #"\s*" %)
                                    (str/split-lines (fixture "relationships.txt"))))
        cases (edn/read-string (fixture "spicedb-results.edn"))
        objects (-> #{}
                    (into (mapcat (fn [{:keys [subject resource]}] [(:id subject) (:id resource)]))
                          relationships)
                    (into (mapcat (fn [{{:keys [subject resource]} :request}]
                                    (keep second [subject resource])))
                          cases)
                    (into (vals unmentioned))
                    (disj "*"))
        conn (datascript/create-conn)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (ds/transact! conn (mapv (fn [id] {:eacl/id id}) objects))
    (eacl/write-schema! client (fixture "schema.zed"))
    (eacl/write-relationships!
     client (mapv (fn [relationship] {:operation :create :relationship relationship})
                  relationships))
    (is (= 72 (count cases)))
    (doseq [case cases]
      (assert-case! client relationships cases case))))
