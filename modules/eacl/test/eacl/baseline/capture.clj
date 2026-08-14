(ns eacl.baseline.capture
  "Frozen public-API baselines for the stable-discovery change (tasks 2.2-2.5).

  Captures denotations, pagination behavior, point checks, counts, cursor
  fork/idempotence, cancellation, and stale-basis outcomes for the current
  engines through the public API on the DataScript backend, and writes them
  as EDN snapshots under exploration/baselines/.

  The snapshots freeze the CURRENT engines as differential oracles before the
  accepted stable-discovery engine replaces them. Result-set (denotation)
  content is authoritative; the recorded :order vectors are informational
  only — legacy page order is explicitly NOT an oracle for the replacement
  engine (see the change's design, Decision 1).

  Regenerate with: (eacl.baseline.capture/capture-all!)
  Verify with:     eacl.baseline.baseline-test"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [datascript.core :as ds]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.execution :as execution]))

(def snapshot-dir "exploration/baselines")

(def capture-page-size
  "Odd page size so page boundaries fall inside sibling groups."
  7)

;; ---------------------------------------------------------------------------
;; Fixture definitions
;; ---------------------------------------------------------------------------

(def folder-schema
  "definition user {}

   definition folder {
     relation parent: folder
     relation member: user
     permission view = member + parent->view
   }")

(def mutual-schema
  "definition user {}

   definition a_node {
     relation link: b_node
     relation direct: user
     permission view = direct + link->view
   }

   definition b_node {
     relation link: a_node
     relation direct: user
     permission view = direct + link->view
   }")

(def wide-schema
  "definition user {}

   definition wide {
     relation r0: user
     relation r1: user
     relation r2: user
     relation r3: user
     relation r4: user
     relation r5: user
     relation r6: user
     relation r7: user
     permission view = r0 + r1 + r2 + r3 + r4 + r5 + r6 + r7
   }")

(defn- obj [type id] (eacl/spice-object type id))

(defn- pad2 [n] (format "%02d" n))

(defn- folder-chain-fixture
  "Recursive chain: f-01 <- f-02 <- ... <- f-20 via parent; alice is a member
  of f-01 and reaches every folder through parent->view."
  []
  (let [folders (mapv #(obj :folder (str "f-" (pad2 %))) (range 1 21))
        alice (obj :user "alice")
        stranger (obj :user "stranger")]
    {:schema folder-schema
     :objects (into [alice stranger] folders)
     :relationships
     (into [(eacl/->Relationship alice :member (first folders))]
           (map (fn [[parent child]]
                  (eacl/->Relationship parent :parent child))
                (partition 2 1 folders)))
     :resource-type :folder
     :permission :view
     :principals {:alice alice :stranger stranger}
     :reverse-resources {:leaf (obj :folder "f-20")
                         :root (obj :folder "f-01")}}))

(defn- group-star-fixture
  "Recursive star: thirty groups all have g-root as parent; root-user is a
  member of g-root; each leaf also has its own member."
  []
  (let [root (obj :group "g-root")
        leaves (mapv #(obj :group (str "g-" (pad2 %))) (range 1 31))
        root-user (obj :user "root-user")
        leaf-users (mapv #(obj :user (str "leaf-user-" (pad2 %))) (range 1 31))]
    {:schema (string/replace folder-schema "folder" "group")
     :objects (into [root root-user] (concat leaves leaf-users))
     :relationships
     (into [(eacl/->Relationship root-user :member root)]
           (mapcat (fn [leaf leaf-user]
                     [(eacl/->Relationship root :parent leaf)
                      (eacl/->Relationship leaf-user :member leaf)])
                   leaves leaf-users))
     :resource-type :group
     :permission :view
     :principals {:root-user root-user
                  :leaf-user (first leaf-users)
                  :stranger (obj :user "stranger")}
     :reverse-resources {:leaf (obj :group "g-30")
                         :root root}}))

(defn- mutual-mixed-fixture
  "Mutual recursion across two definitions with a data cycle: a-1 -> b-1 ->
  a-2 -> b-2 -> a-3 -> b-3 -> a-1. carol is direct on a-1; dave is direct on
  b-2 only."
  []
  (let [as (mapv #(obj :a_node (str "a-" %)) (range 1 4))
        bs (mapv #(obj :b_node (str "b-" %)) (range 1 4))
        carol (obj :user "carol")
        dave (obj :user "dave")]
    {:schema mutual-schema
     :objects (into [carol dave] (concat as bs))
     ;; link edges point child -> inherited-from, i.e. X gains view from
     ;; link->view. Cycle: a-1 links b-3, b-1 links a-1, a-2 links b-1, ...
     :relationships
     [(eacl/->Relationship carol :direct (as 0))
      (eacl/->Relationship dave :direct (bs 1))
      (eacl/->Relationship (bs 2) :link (as 0))
      (eacl/->Relationship (as 0) :link (bs 0))
      (eacl/->Relationship (bs 0) :link (as 1))
      (eacl/->Relationship (as 1) :link (bs 1))
      (eacl/->Relationship (bs 1) :link (as 2))
      (eacl/->Relationship (as 2) :link (bs 2))]
     :resource-type :a_node
     :permission :view
     :principals {:carol carol :dave dave :stranger (obj :user "stranger")}
     :reverse-resources {:a-1 (as 0) :a-3 (as 2)}}))

(defn- cyclic-data-fixture
  "Pure data cycle on the folder schema: c-1 -> c-2 -> c-3 -> c-1 via parent;
  erin is a member of c-1. Termination and exact dedup are exercised."
  []
  (let [folders (mapv #(obj :folder (str "c-" %)) (range 1 4))
        erin (obj :user "erin")]
    {:schema folder-schema
     :objects (into [erin] folders)
     :relationships
     [(eacl/->Relationship erin :member (folders 0))
      (eacl/->Relationship (folders 0) :parent (folders 1))
      (eacl/->Relationship (folders 1) :parent (folders 2))
      (eacl/->Relationship (folders 2) :parent (folders 0))]
     :resource-type :folder
     :permission :view
     :principals {:erin erin :stranger (obj :user "stranger")}
     :reverse-resources {:c-2 (folders 1)}}))

(defn- broad-union-fixture
  "Broad union: view = r0 + ... + r7 over twelve resources. early-user is
  granted via r0 on three resources; late-user only via r7 on w-09 (the
  late-productive principal); filler users keep interior relations non-empty."
  []
  (let [wides (mapv #(obj :wide (str "w-" (pad2 %))) (range 1 13))
        early (obj :user "early-user")
        late (obj :user "late-user")
        fillers (mapv #(obj :user (str "filler-" (pad2 %))) (range 1 13))]
    {:schema wide-schema
     :objects (into [early late] (concat wides fillers))
     :relationships
     (into [(eacl/->Relationship early :r0 (wides 0))
            (eacl/->Relationship early :r0 (wides 4))
            (eacl/->Relationship early :r0 (wides 10))
            (eacl/->Relationship late :r7 (wides 8))]
           (map-indexed (fn [i wide]
                          (eacl/->Relationship (fillers i) :r3 wide))
                        wides))
     :resource-type :wide
     :permission :view
     :principals {:early early :late late :stranger (obj :user "stranger")}
     :reverse-resources {:w-09 (wides 8) :w-01 (wides 0)}}))

(def explorer-baseline-shape
  "Small deterministic Explorer shape: overlapping account/team/VPC arrows,
  a dense super-user, a narrow user-1, one single-account owner."
  (assoc fixture/default-shape
         :accounts 3
         :teams-per-account 2
         :vpcs-per-account 2
         :servers-per-account 12
         :user-1-account-count 2))

(defn- explorer-fixture
  "Deployment-shaped acyclic fixture: direct, union-overlap, deep arrow, and
  a dense principal, from the shared explorer fixture."
  []
  {:schema fixture/schema
   :objects (vec (fixture/objects explorer-baseline-shape))
   :relationships (vec (fixture/relationships explorer-baseline-shape))
   :resource-type :server
   :permission :view
   :principals {:super-user fixture/super-user
                :user-1 fixture/user-1
                :owner-0001 fixture/owner-0001
                :stranger (obj :user "stranger")}
   :reverse-resources
   {:server-1-1 (obj :server (fixture/server-id 0 0))
    :server-3-12 (obj :server (fixture/server-id 2 11))}})

(defn- explorer-recursive-fixture
  "Deployment-shaped recursive fixture: account parent chains on the
  recursive schema (populated parent relation)."
  []
  (let [shape (assoc fixture/populated-recursive-shape
                     :accounts 12
                     :teams-per-account 1
                     :vpcs-per-account 1
                     :servers-per-account 3
                     :user-1-account-count 3
                     :subaccount-count 6)]
    {:schema fixture/recursive-schema
     :objects (vec (fixture/objects shape))
     :relationships (vec (fixture/populated-recursive-relationships shape))
     :resource-type :server
     :permission :view
     :principals {:super-user fixture/super-user
                  :user-1 fixture/user-1
                  :owner-0001 fixture/owner-0001
                  :stranger (obj :user "stranger")}
     :reverse-resources
     {:server-1-1 (obj :server (fixture/server-id 0 0))
      :server-5-1 (obj :server (fixture/server-id 4 0))}}))

(def fixtures
  {:explorer-acyclic explorer-fixture
   :explorer-recursive explorer-recursive-fixture
   :folder-chain folder-chain-fixture
   :group-star group-star-fixture
   :mutual-mixed mutual-mixed-fixture
   :cyclic-data cyclic-data-fixture
   :broad-union broad-union-fixture})

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn seed-client!
  "Builds a DataScript-backed public client for one fixture with answer
  caching disabled and a fixed source lifecycle."
  [{:keys [schema objects relationships]}]
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:cache {:remember-answers false}
                 :source-lifecycle "stable-discovery-baseline"})]
    (eacl/write-schema! client schema)
    (ds/transact! conn
                  (vec (map-indexed
                        (fn [index {:keys [id]}]
                          {:db/id (- (inc index)) :eacl/id id})
                        objects)))
    (doseq [batch (partition-all 500 relationships)]
      (eacl/create-relationships! client (vec batch)))
    {:conn conn :client client}))

;; ---------------------------------------------------------------------------
;; Capture helpers
;; ---------------------------------------------------------------------------

(defn- object-ref [{:keys [type id]}]
  (str (name type) ":" id))

(defn- error-class [f]
  (try
    {:outcome :ok :value (f)}
    (catch clojure.lang.ExceptionInfo error
      {:outcome :error
       :error-keys (->> (ex-data error)
                        ((juxt :eacl/error :error :key :type))
                        (remove nil?)
                        vec)})
    (catch Throwable t
      {:outcome :throw :class (.getName (class t))})))

(defn- paginate-all
  "Follows :after cursors to exhaustion. Returns the concatenated order,
  page sizes, and page-info flags."
  [client query]
  (loop [query query
         pages []
         order []]
    (let [{:keys [data page-info]} (eacl/lookup-resources client query)
          order (into order (map object-ref) data)
          pages (conj pages {:size (count data)
                             :has-next? (boolean (:has-next-page? page-info))
                             :has-previous? (boolean (:has-previous-page? page-info))})]
      (if (and (:has-next-page? page-info) (:end-cursor page-info) (seq data))
        (recur (assoc query :after (:end-cursor page-info)) pages order)
        {:order order :pages pages}))))

(defn- forward-baseline
  [client fixture principal-key principal]
  (let [{:keys [resource-type permission]} fixture
        query {:subject principal
               :permission permission
               :resource/type resource-type
               :first capture-page-size}
        paged (paginate-all client query)
        one-shot (eacl/lookup-resources
                  client (assoc query :first 1000))
        one-shot-order (mapv object-ref (:data one-shot))
        count-result (eacl/count-resources
                      client {:subject principal
                              :permission permission
                              :resource/type resource-type})]
    {:principal (object-ref principal)
     :denotation (vec (sort (distinct (:order paged))))
     :order (:order paged)
     :pages (:pages paged)
     :page-composition-equals-one-shot? (= (:order paged) one-shot-order)
     :duplicate-free? (= (count (:order paged))
                         (count (distinct (:order paged))))
     :count (select-keys count-result [:count :limit :truncated?])
     :count-matches-denotation?
     (= (:count count-result) (count (distinct (:order paged))))}))

(defn- reverse-baseline
  [client fixture resource]
  (let [{:keys [permission]} fixture
        query {:resource resource
               :permission permission
               :subject/type :user
               :first capture-page-size}
        result (error-class #(eacl/lookup-subjects client query))]
    (if (= :ok (:outcome result))
      (let [{:keys [data]} (:value result)
            ;; follow pagination to exhaustion
            paged (loop [query query order []]
                    (let [{:keys [data page-info]}
                          (eacl/lookup-subjects client query)
                          order (into order (map object-ref) data)]
                      (if (and (:has-next-page? page-info)
                               (:end-cursor page-info)
                               (seq data))
                        (recur (assoc query :after (:end-cursor page-info))
                               order)
                        order)))]
        {:resource (object-ref resource)
         :outcome :ok
         :denotation (vec (sort (distinct paged)))
         :order paged
         :first-page-size (count data)})
      (assoc result :resource (object-ref resource)))))

(defn- point-baseline
  [client fixture forward-results]
  (let [{:keys [permission principals]} fixture
        samples
        (for [[principal-key result] forward-results
              :let [principal (get principals principal-key)
                    authorized (first (:denotation result))
                    denotation (set (:denotation result))]
              :when principal]
          (let [foreign (first
                         (remove denotation
                                 (mapcat :denotation (vals forward-results))))]
            [principal-key
             (cond-> {}
               authorized
               (assoc :authorized-sample
                      (let [[type id] (string/split authorized #":" 2)]
                        {:resource authorized
                         :can? (eacl/can? client
                                          {:subject principal
                                           :permission permission
                                           :resource (obj (keyword type) id)})}))
               foreign
               (assoc :unauthorized-sample
                      (let [[type id] (string/split foreign #":" 2)]
                        {:resource foreign
                         :can? (eacl/can? client
                                          {:subject principal
                                           :permission permission
                                           :resource (obj (keyword type) id)})})))]))]
    (into {} samples)))

(defn- cursor-behavior-baseline
  "Cursor idempotence, fork reuse, cancellation, and stale-basis outcomes for
  one representative principal."
  [fixture]
  (let [{:keys [resource-type permission principals]} fixture
        principal (val (first principals))
        {:keys [client]} (seed-client! fixture)
        query {:subject principal
               :permission permission
               :resource/type resource-type
               :first 2}
        page-1 (eacl/lookup-resources client query)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        page-2 (when cursor-1
                 (eacl/lookup-resources client (assoc query :after cursor-1)))
        page-1-again (eacl/lookup-resources client query)
        page-2-again (when cursor-1
                       (eacl/lookup-resources
                        client (assoc query :after cursor-1)))
        cancelled-token (doto (execution/cancellation-token)
                          (execution/cancel!))
        cancelled (error-class
                   #(eacl/lookup-resources
                     client (assoc query :cancellation-token cancelled-token)))
        invalid-timeout (error-class
                         #(eacl/lookup-resources
                           client (assoc query :timeout-ms 0)))]
    {:first-page-repeat-identical?
     (= (mapv object-ref (:data page-1))
        (mapv object-ref (:data page-1-again)))
     :after-cursor-repeat-identical?
     (= (some->> page-2 :data (mapv object-ref))
        (some->> page-2-again :data (mapv object-ref)))
     :parent-cursor-reusable-after-child? (some? page-2-again)
     :cancelled-request cancelled
     ;; Deadline expiry itself is timing-dependent and deliberately not part
     ;; of a frozen snapshot; the cancelled-token probe covers the same
     ;; typed check! path deterministically.
     :zero-timeout-rejected (select-keys invalid-timeout
                                         [:outcome :error-keys])}))

(defn- stale-basis-baseline
  "Mints a cursor, writes an unrelated relationship, then resumes: documents
  the current engine's continuation behavior across a basis change."
  [fixture]
  (let [{:keys [resource-type permission principals]} fixture
        principal (val (first principals))
        {:keys [conn client]} (seed-client! fixture)
        query {:subject principal
               :permission permission
               :resource/type resource-type
               :first 2}
        page-1 (eacl/lookup-resources client query)
        cursor (get-in page-1 [:page-info :end-cursor])]
    (if-not cursor
      {:outcome :no-cursor}
      (do
        ;; Unrelated write: a new object entity advances the backend basis
        ;; without touching any relationship the query depends on.
        (ds/transact! conn [{:db/id -1 :eacl/id "stale-basis-object"}])
        (let [resumed (error-class
                       #(eacl/lookup-resources
                         client (assoc query :after cursor)))]
          {:outcome (:outcome resumed)
           :detail (dissoc resumed :value)
           :resumed-page-size
           (when (= :ok (:outcome resumed))
             (count (:data (:value resumed))))})))))

;; ---------------------------------------------------------------------------
;; Capture entry points
;; ---------------------------------------------------------------------------

(defn capture-fixture
  [fixture-key]
  (let [fixture ((get fixtures fixture-key))
        {:keys [client]} (seed-client! fixture)
        forward (into (sorted-map)
                      (for [[principal-key principal] (:principals fixture)]
                        [principal-key
                         (forward-baseline client fixture
                                           principal-key principal)]))
        reverse-results (into (sorted-map)
                              (for [[label resource]
                                    (:reverse-resources fixture)]
                                [label (reverse-baseline client fixture
                                                         resource)]))]
    {:fixture fixture-key
     :page-size capture-page-size
     :forward forward
     :reverse reverse-results
     :points (point-baseline client fixture forward)
     :cursor-behavior (cursor-behavior-baseline fixture)
     :stale-basis (stale-basis-baseline fixture)}))

(defn- snapshot-file [fixture-key]
  (io/file snapshot-dir (str (name fixture-key) ".edn")))

(defn write-snapshot!
  [fixture-key capture]
  (let [file (snapshot-file fixture-key)]
    (io/make-parents file)
    (with-open [writer (io/writer file)]
      (binding [*out* writer]
        (println ";; Frozen current-engine baseline. Regenerate via")
        (println ";; (eacl.baseline.capture/capture-all!) — see README.md.")
        (pprint/pprint capture)))
    file))

(defn read-snapshot
  [fixture-key]
  (let [file (snapshot-file fixture-key)]
    (when (.exists file)
      (read-string (slurp file)))))

(defn capture-all!
  "Regenerates every baseline snapshot. Returns the written file paths."
  []
  (mapv (fn [fixture-key]
          (str (write-snapshot! fixture-key (capture-fixture fixture-key))))
        (keys fixtures)))
