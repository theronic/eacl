(ns eacl.bench.drive-parity-fixture
  "The Drive-style benchmark graph of the eacl-rust port (`crates/eacl-bench`,
  generator `src/gen.rs`, Clojure mirror `clj/eacl_bench/bench.clj`), loaded
  into DataScript through the public client.

  The generator draws from one SplitMix64 stream in the Rust generator's order,
  so both implementations build the same relationships; `checksum` is the
  FNV-1a digest of their canonical lines, which the Rust generator reports for
  the same scale. The schema is the Rust benchmark's, plus two reference
  permissions on `doc` (`parent_view` and `reviewed`, the operands of
  `review`) that only the gate reads.

  The non-linear chain is a separate graph: `reach` recurses through an
  intersection of two recursive operands, which neither delegation nor the
  guarded search covers; `reach_union` is its union twin."
  (:require [datascript.core :as ds]
            [eacl.caveats.jvm :as caveats]
            [eacl.core :as eacl]
            [eacl.datascript.core :as eacl.datascript]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- generator

(def now-ms 1700000000000)
(def ^:private exp-active (+ now-ms 1000000000))
(def ^:private exp-expired (- now-ms 1))
(def ^:private seed 20261001)
(def ^:private chain 64)
(def ^:private special-users 5)
(def allowed-ip "10.0.0.1")

(def schema
  "caveat ip_ok(ip string, allowed list<string>) {
  ip in allowed
}

definition user {}

definition group {
  relation direct: user
  relation sub: group
  permission member = direct + sub->member
}

definition folder {
  relation parent: folder
  relation viewer: user
  relation viewer_group: group
  permission view = viewer + viewer_group->member + parent->view
}

definition doc {
  relation parent: folder
  relation owner: user
  relation viewer: user | user with ip_ok
  relation banned: user
  relation reviewer: user
  permission view = (viewer + owner + parent->view) - banned
  permission view_any = viewer + owner + parent->view
  permission edit = owner
  permission review = parent->view & reviewer
  permission parent_view = parent->view
  permission reviewed = reviewer
}
")

(def ^:private ^:const golden -7046029254386353131)   ; 0x9E3779B97F4A7C15
(def ^:private ^:const mix1 -4658895280553007687)     ; 0xBF58476D1CE4E5B9
(def ^:private ^:const mix2 -7723592293110705685)     ; 0x94D049BB133111EB

(defn- next-u64! ^long [^longs st]
  (let [s (unchecked-add (aget st 0) golden)
        _ (aset st 0 s)
        z (unchecked-multiply (bit-xor s (unsigned-bit-shift-right s 30)) mix1)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27)) mix2)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn- below! ^long [^longs st ^long n]
  (Long/remainderUnsigned (next-u64! st) n))

(defn first-draw
  "The first value of a SplitMix64 stream seeded with 0 (`e220a8397b1dcdaf`)."
  []
  (format "%016x" (next-u64! (long-array [0]))))

(defn scale
  [label]
  (let [docs (case label "1e4" 3000 "1e5" 30000 "1e6" 300000)
        users (quot docs 5)]
    {:label label :docs docs :folders (quot docs 10) :users users
     :groups (max 10 (quot users 20))}))

(def expected
  "Relationship counts and checksums the Rust generator reports."
  {"1e4" {:relationships 10054 :checksum "c9d636baad405df9"}
   "1e5" {:relationships 98799 :checksum "dcbb5c45b6fd0c5d"}
   "1e6" {:relationships 987394 :checksum "c2e110f5afdeb7ec"}})

(defn- dg-folder
  [{:keys [folders]}]
  (loop [x 1] (if (< (+ (* 3 x) 10) folders) (recur (+ (* 3 x) 10)) x)))

(defn object-ids
  [{:keys [users groups folders docs]}]
  (vec (concat (map #(str "u" %) (range users))
               (map #(str "g" %) (range groups))
               (map #(str "f" %) (range folders))
               (map #(str "c" %) (range chain))
               (map #(str "d" %) (range docs))
               ["da" "dg" "dc"])))

;; A relationship: [subject-type subject-id relation resource-type resource-id qualifier]
(defn- rel [st sid r rt rid] [st sid r rt rid nil])

(defn relationships
  [{:keys [docs folders users groups] :as sc}]
  (let [st (long-array [seed])
        d docs f folders u users g groups
        out (transient [])
        user-pick (fn [] (+ special-users (below! st (- u special-users))))
        U (fn [i] (str "u" i)) G (fn [i] (str "g" i)) F (fn [i] (str "f" i))
        C (fn [i] (str "c" i)) D (fn [i] (str "d" i))]
    ;; 1. group hierarchy
    (doseq [i (range 1 g)]
      (conj! out (rel :group (G i) :sub :group (G (quot (dec i) 4)))))
    ;; 2. memberships
    (conj! out (rel :user (U 0) :direct :group (G 1)))
    (conj! out (rel :user (U 2) :direct :group (G 2)))
    (doseq [j (range special-users u)]
      (let [x1 (below! st 100) x2 (below! st 100) r (below! st g)
            a (mod j g) first? (< x1 60)]
        (when first? (conj! out (rel :user (U j) :direct :group (G a))))
        (when (and (< x2 20) (not (and first? (== r a))))
          (conj! out (rel :user (U j) :direct :group (G r))))))
    ;; 3. folder forest
    (doseq [i (range 10 f)]
      (conj! out (rel :folder (F (quot (- i 10) 3)) :parent :folder (F i))))
    ;; 4. folder viewers
    (conj! out (rel :group (G 0) :viewer_group :folder (F 0)))
    (doseq [i (range 1 5)] (conj! out (rel :group (G 1) :viewer_group :folder (F i))))
    (doseq [i (range 5 10)] (conj! out (rel :group (G 2) :viewer_group :folder (F i))))
    (conj! out (rel :user (U 1) :viewer :folder (F (- f 1))))
    (conj! out (rel :user (U 1) :viewer :folder (F (- f 2))))
    (let [quarter (quot g 4)]
      (doseq [i (range f)]
        (let [x1 (below! st 100) ru (user-pick) x2 (below! st 100)
              rg (+ quarter (below! st (- g quarter)))]
          (when (< x1 30) (conj! out (rel :user (U ru) :viewer :folder (F i))))
          (when (and (< x2 10) (>= i 10))
            (conj! out (rel :group (G rg) :viewer_group :folder (F i)))))))
    ;; 5. chain
    (doseq [k (range 1 chain)] (conj! out (rel :folder (C (dec k)) :parent :folder (C k))))
    (conj! out (rel :user (U 3) :viewer :folder (C 0)))
    (doseq [k (range chain)]
      (let [ru (user-pick)] (conj! out (rel :user (U ru) :viewer :folder (C k)))))
    (conj! out (rel :folder (C (dec chain)) :parent :doc "dc"))
    (conj! out (rel :user (U 5) :owner :doc "dc"))
    ;; 6. special docs
    (conj! out (rel :folder (F 0) :parent :doc "da"))
    (conj! out (rel :user (U 5) :owner :doc "da"))
    (conj! out (rel :folder (F (dg-folder sc)) :parent :doc "dg"))
    (conj! out (rel :user (U 5) :owner :doc "dg"))
    ;; 7. docs
    (let [twentieth (quot d 20)]
      (doseq [k (range d)]
        (let [fo (below! st f) ow (user-pick) xv (below! st 100) rv (user-pick)
              xq (below! st 1000) xb (below! st 100) rb (user-pick) xr (below! st 100)
              rr (user-pick)]
          (conj! out (rel :folder (F fo) :parent :doc (D k)))
          (conj! out (rel :user (U ow) :owner :doc (D k)))
          (when (< xv 70)
            (let [q (cond (< xq 2) :caveat (< xq 102) :active (< xq 122) :expired :else nil)]
              (conj! out [:user (U rv) :viewer :doc (D k) q])))
          (when (< xb 2) (conj! out (rel :user (U rb) :banned :doc (D k))))
          (when (< xr 5) (conj! out (rel :user (U rr) :reviewer :doc (D k))))
          (when (== 50 (mod k 100)) (conj! out (rel :user (U 0) :banned :doc (D k))))
          (when (== 3 (mod k 5)) (conj! out (rel :user (U 2) :reviewer :doc (D k))))
          (when (and (pos? twentieth) (== 7 (mod k twentieth)))
            (conj! out (rel :user (U 1) :viewer :doc (D k))))
          (when (and (pos? twentieth) (== 11 (mod k twentieth)))
            (conj! out (rel :user (U 0) :reviewer :doc (D k)))))))
    (persistent! out)))

(defn- canonical-line
  [[st sid r rt rid q]]
  (str (name rt) ":" rid "#" (name r) "@" (name st) ":" sid
       (case q nil "" :caveat "|c" :active "|e+" :expired "|e-")))

(defn checksum
  "FNV-1a over the canonical lines, each followed by a newline."
  [rels]
  (let [h (long-array [(unchecked-long 0xcbf29ce484222325N)])]
    (doseq [r rels]
      (let [^String line (str (canonical-line r) "\n")]
        (dotimes [i (.length line)]
          (aset h 0 (unchecked-multiply (bit-xor (aget h 0) (long (int (.charAt line i))))
                                        0x100000001b3)))))
    (format "%016x" (aget h 0))))

;; ---------------------------------------------------------------- clients and load

(def client-opts
  "The eacl-rust harness's client options: a fixed clock, a fixed key, the
  JVM Caveat evaluator, and traversal limits high enough that no workload is
  cut short."
  {:clock (fn [] now-ms)
   :caveat-evaluator (caveats/evaluator)
   :security-key "eacl-rust-differential-harness-key-0001"
   :recursive-traversal-limits {:max-derived-grants 100000000
                                :max-advanced-datoms 100000000
                                :max-queued-work 100000000}
   :execution-timeout-ms 3600000})

(defn make-client
  ([conn] (make-client conn {}))
  ([conn extra] (eacl.datascript/make-client conn (merge client-opts extra))))

(def ^:private bound-context {"allowed" [allowed-ip]})

(def request-context
  "The caveat context every read carries."
  {"ip" allowed-ip})

(defn spice [t id] (eacl/spice-object t id))

(defn- ->relationship
  [[st sid r rt rid q]]
  (cond-> (eacl/->Relationship (spice st sid) r (spice rt rid))
    (= q :caveat) (assoc :caveat "ip_ok" :caveat-context bound-context)
    (= q :active) (assoc :valid-until-ms exp-active)
    (= q :expired) (assoc :valid-until-ms exp-expired)))

(defn load!
  "Builds the graph of `label` in a fresh DataScript connection: schema, then
  objects in chunks of 10,000, then relationships in batches of 5000, as the
  Rust benchmark loads it. Returns the connection, a client and load facts."
  [label]
  (let [sc (scale label)
        rels (relationships sc)
        conn (eacl.datascript/create-conn)
        acl (make-client conn)
        started (System/nanoTime)]
    (eacl/write-schema! acl schema)
    (doseq [chunk (partition-all 10000 (object-ids sc))]
      (ds/transact! conn (mapv (fn [id] {:eacl/id id}) chunk)))
    (doseq [chunk (partition-all 5000 rels)]
      (eacl/write-relationships! acl (mapv (fn [r] {:operation :create :relationship (->relationship r)}) chunk)))
    {:conn conn :acl acl :scale sc
     :facts {:relationships (count rels) :checksum (checksum rels)
             :load-ms (/ (- (System/nanoTime) started) 1e6)}}))

;; ---------------------------------------------------------------- non-linear recursion

(def chain-schema
  "definition user {}

definition folder {
  relation parent: folder
  relation link: folder
  relation viewer: user
  permission reach = viewer + (parent->reach & link->reach)
  permission reach_union = viewer + parent->reach_union
}
")

(defn load-chain!
  "A chain of `n` folders where each folder's `parent` and `link` are both its
  predecessor, and `u-yes` views the first folder."
  [n]
  (let [conn (eacl.datascript/create-conn)
        acl (make-client conn)]
    (eacl/write-schema! acl chain-schema)
    (ds/transact! conn (into [{:eacl/id "u-yes"} {:eacl/id "u-no"}]
                             (map (fn [i] {:eacl/id (str "f" i)})) (range n)))
    (eacl/write-relationships!
     acl (into [{:operation :create
                 :relationship (eacl/->Relationship (spice :user "u-yes") :viewer (spice :folder "f0"))}]
               (mapcat (fn [i]
                         (for [relation [:parent :link]]
                           {:operation :create
                            :relationship (eacl/->Relationship (spice :folder (str "f" (dec i))) relation
                                                               (spice :folder (str "f" i)))})))
               (range 1 n)))
    {:conn conn :acl acl :n n}))

;; ---------------------------------------------------------------- requests

(defn check
  [acl subject permission resource-type resource opts]
  (:permissionship
   (eacl/check-permission acl (merge {:subject (spice :user subject) :permission permission
                                      :resource (spice resource-type resource)
                                      :caveat-context request-context}
                                     opts))))

(defn checks
  [acl subject permission resources opts]
  (mapv :permissionship
        (eacl/check-permissions acl (merge {:checks (mapv (fn [r] {:subject (spice :user subject)
                                                                    :permission permission
                                                                    :resource (spice :doc r)})
                                                          resources)
                                            :caveat-context request-context}
                                           opts))))

(defn page-ids
  [acl subject permission size opts]
  (mapv :id (:data (eacl/lookup-resources acl (merge {:subject (spice :user subject) :permission permission
                                                       :resource/type :doc :first size
                                                       :caveat-context request-context}
                                                      opts)))))

(defn subject-ids
  [acl resource permission size opts]
  (mapv :id (:data (eacl/lookup-subjects acl (merge {:resource (spice :doc resource) :permission permission
                                                      :subject/type :user :first size
                                                      :caveat-context request-context}
                                                     opts)))))

(defn count-of
  [acl subject permission limit opts]
  (:count (eacl/count-resources acl (cond-> (merge {:subject (spice :user subject) :permission permission
                                                     :resource/type :doc
                                                     :caveat-context request-context}
                                                    opts)
                                      limit (assoc :count-limit limit)))))

(defn walk-ids
  "Every resource id of a walk in pages of `size`, following end cursors."
  [acl subject permission size opts]
  (loop [after nil out (transient [])]
    (let [page (eacl/lookup-resources acl (cond-> (merge {:subject (spice :user subject) :permission permission
                                                          :resource/type :doc :first size
                                                          :caveat-context request-context}
                                                         opts)
                                            after (assoc :after after)))
          out (reduce conj! out (map :id (:data page)))
          {:keys [has-next-page? end-cursor]} (:page-info page)]
      (if (and has-next-page? end-cursor)
        (recur end-cursor out)
        (persistent! out)))))

(defn typical-denied-doc
  "The document the report's denied typical check uses: no direct grant to
  the narrow subject, reached only through its folder ancestry."
  [{:keys [docs]}]
  (str "d" (quot docs 2)))

(defn ids [prefix n] (mapv #(str prefix %) (range n)))
