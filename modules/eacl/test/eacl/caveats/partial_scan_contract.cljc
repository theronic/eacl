(ns eacl.caveats.partial-scan-contract
  "Cross-backend contract for owner-unanchored `read-relationships` walks over
  Relations that hold qualified rows (EACL-FORMAL-076).

  A scan without `:subject/id` and `:resource/id` reads the AVET index, whose
  values `[p0 p1 p2 primary qualifier]` order one primary endpoint's rows by
  qualifier (plain rows first) before owner. Every walk below must list each
  stored row exactly once, in the order of one large page, and terminate."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as string]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(def schema
  (str "caveat enabled(flag bool) { flag }\n"
       "definition user {}\n"
       "definition org {\n"
       " relation child: org\n"
       " relation viewer: user | user with enabled\n"
       " permission see = viewer\n"
       "}"))

(defn- identity-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) relation (:type resource) (:id resource)])

(defn- walk
  "Follows one forward or backward walk and returns its rows in ascending
  page order. Stops, failing the test, if the walk exceeds `max-pages`."
  [client query direction size max-pages]
  (loop [request (assoc query (case direction :asc :first :desc :last) size)
         pages []]
    (if (> (count pages) max-pages)
      (do (is false (str "read-relationships walk did not terminate: "
                         (pr-str [query direction size])))
          [])
      (let [page (eacl/read-relationships client request)
            pages (conj pages (:data page))
            info (:page-info page)]
        (case direction
          :asc (if (:has-next-page? info)
                 (recur (assoc request :after (:end-cursor info)) pages)
                 (into [] cat pages))
          :desc (if (:has-previous-page? info)
                  (recur (assoc request :before (:start-cursor info)) pages)
                  (into [] cat (reverse pages))))))))

(defn- check-walks!
  "Every walk of `query` equals one large page of it, including order."
  [client query expected-set]
  (let [single (:data (eacl/read-relationships client (assoc query :first 1000)))
        max-pages (+ 2 (count single))]
    (when expected-set
      (is (= expected-set (set (map identity-key single))) (pr-str query)))
    (doseq [[direction size] [[:asc 1] [:asc 2] [:asc 3] [:desc 1] [:desc 2]]]
      (let [walked (walk client query direction size max-pages)]
        (is (= single walked) (pr-str [query direction size]))
        (is (= (count walked) (count (distinct (map identity-key walked))))
            (pr-str [query direction size]))))
    single))

(defn- check-eacl-rs-004!
  "The three minimized EACL-RS-004 walks: `org:f child org:c` is expiring and
  `org:f child org:f` is plain, so the plain row precedes the qualified row
  of the same subject although org:c has the smaller eid."
  [{:keys [client writer now]}]
  (let [native (:native (writer))
        org (fn [id] (eacl/spice-object :org id))
        expiring (assoc (eacl/->Relationship (org "rs004/f") :child (org "rs004/c"))
                        :valid-until-ms 9000000)
        plain (eacl/->Relationship (org "rs004/f") :child (org "rs004/f"))
        query {:resource/type :org :resource/relation :child :cache? false}]
    (reset! now 2000000)
    ((:transact! native) [{:eacl/id "rs004/c"} {:eacl/id "rs004/f"} {:eacl/id "rs004/u"}])
    (eacl/write-relationships! client [{:operation :create :relationship expiring}
                                       {:operation :create :relationship plain}])
    (testing "stored rows: the :first 1 walk lists the qualified row"
      (is (= #{plain expiring}
             (set (walk client query :asc 1 4))
             (set (:data (eacl/read-relationships client (assoc query :first 1000)))))))
    (testing ":expiry-active walk lists both rows once and terminates"
      (is (= [plain expiring]
             (walk client (assoc query :relationship-state :expiry-active) :asc 1 4))))
    (testing ":authorization walk ends after one empty page"
      (let [page (eacl/read-relationships
                  client (assoc query :first 1
                                :authorization {:subject (eacl/spice-object :user "rs004/u")
                                                :permission :see :on :resource}))]
        (is (= [] (:data page)))
        (is (false? (get-in page [:page-info :has-next-page?])))
        (is (false? (get-in page [:page-info :bounded?])))))
    (eacl/delete-relationships! client [expiring plain])))

(defn- check-plain-groups!
  "Plain rows only: a continuation from the first primary group must not
  filter later groups by the boundary row's owner. `org:p1` holds the owner
  with the larger eid, `org:p2` the smaller one."
  [{:keys [client writer]}]
  (let [native (:native (writer))
        org (fn [id] (eacl/spice-object :org id))
        first-group (eacl/->Relationship (org "groups/high") :child (org "groups/p1"))
        second-group (eacl/->Relationship (org "groups/low") :child (org "groups/p2"))
        query {:subject/type :org :resource/type :org :cache? false}]
    ((:transact! native) [{:eacl/id "groups/low"} {:eacl/id "groups/p1"}
                          {:eacl/id "groups/p2"} {:eacl/id "groups/high"}])
    (eacl/write-relationships! client [{:operation :create :relationship first-group}
                                       {:operation :create :relationship second-group}])
    (is (= [first-group second-group]
           (:data (eacl/read-relationships client (assoc query :first 1000)))))
    (doseq [direction [:asc :desc]]
      (is (= [first-group second-group] (walk client query direction 1 4))
          (pr-str direction)))
    (eacl/delete-relationships! client [first-group second-group])))

(defn- next-random
  [state]
  (mod (* (max 1 state) 48271) 2147483647))

(defn- choose
  [state values]
  (let [state (next-random state)]
    [state (nth values (mod state (count values)))]))

(defn- shuffle-seeded
  [state values]
  (loop [state state remaining (vec values) shuffled []]
    (if (empty? remaining)
      [state shuffled]
      (let [state (next-random state)
            index (mod state (count remaining))]
        (recur state
               (into (subvec remaining 0 index) (subvec remaining (inc index)))
               (conj shuffled (nth remaining index)))))))

(def ^:private qualifiers
  [{} {} {:valid-until-ms 1500} {:valid-until-ms 2500} {:valid-until-ms 3500}
   {:caveat "enabled"} {:caveat "enabled" :caveat-context {"flag" true}}
   {:caveat "enabled" :valid-until-ms 3000}])

(defn- check-random-walks!
  "Seeded relationships over small endpoint domains, then qualifier-replacing
  touches, which allocate newer qualifier eids so qualifier order disagrees
  with owner order inside primary groups."
  [{:keys [client writer now]} seed]
  (let [native (:native (writer))
        prefix (str "walk" seed "/")
        users (mapv #(eacl/spice-object :user (str prefix "u" %)) (range 4))
        orgs (mapv #(eacl/spice-object :org (str prefix "o" %)) (range 4))
        [state ordered] (shuffle-seeded seed (concat users orgs))
        _ ((:transact! native) (mapv #(hash-map :eacl/id (:id %)) ordered))
        candidates (vec (concat (for [u users o orgs] [u :viewer o])
                                (for [a orgs b orgs] [a :child b])))
        [state chosen] (shuffle-seeded state candidates)
        chosen (subvec chosen 0 14)
        assign (fn [state triples]
                 (reduce (fn [[state rows] [subject relation resource]]
                           (let [[state qualifier]
                                 (choose state (if (= :child relation)
                                                 (filterv #(not (:caveat %)) qualifiers)
                                                 qualifiers))]
                             [state (conj rows (merge (eacl/->Relationship subject relation resource)
                                                      qualifier))]))
                         [state []]
                         triples))
        _ (reset! now 1000)
        [state created] (assign state chosen)
        _ (eacl/write-relationships! client (mapv #(hash-map :operation :create :relationship %) created))
        [state renewed-triples] (shuffle-seeded state (subvec chosen 0 7))
        [_ renewed] (assign state renewed-triples)
        _ (eacl/write-relationships! client (mapv #(hash-map :operation :touch :relationship %) renewed))
        stored (vals (merge (into {} (map (juxt identity-key identity)) created)
                            (into {} (map (juxt identity-key identity)) renewed)))
        expected (fn [pred] (into #{} (comp (filter pred) (map identity-key)) stored))]
    (reset! now 2000)
    (doseq [[query pred]
            [[{:resource/type :org} #(= :org (get-in % [:resource :type]))]
             [{:resource/type :org :resource/relation :viewer} #(= :viewer (:relation %))]
             [{:resource/type :org :resource/relation :child} #(= :child (:relation %))]
             [{:subject/type :user} #(= :user (get-in % [:subject :type]))]
             [{:subject/type :org :resource/type :org} #(= :child (:relation %))]]
            :let [query (assoc query :cache? false)
                  owned? #(string/starts-with? (get-in % [:subject :id]) prefix)]]
      (testing (pr-str {:seed seed :query query})
        (check-walks! client query (expected #(and (owned? %) (pred %))))
        (check-walks! client (dissoc query :cache?) nil)
        (let [active (check-walks! client (assoc query :relationship-state :expiry-active) nil)]
          (is (= (expected #(and (owned? %) (pred %)
                                 (let [deadline (:valid-until-ms %)]
                                   (or (nil? deadline) (< 2000 deadline)))))
                 (into #{} (comp (filter owned?) (map identity-key)) active))))
        (when (= :org (:resource/type query))
          (check-walks! client
                        (assoc query :authorization
                               {:subject (first users) :permission :see :on :resource})
                        nil))))
    (eacl/delete-relationships! client (vec stored))))

(defn check!
  "Runs a seeded sweep and the minimized EACL-RS-004 walks on one backend.
  `env` carries the qualified `:client`, its `:writer`, and the `:now` clock
  atom, as for `eacl.caveats.inspection-contract/check!`. The client clock
  never moves backwards, so the sweep (clock 1000-2000) runs first."
  [env]
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (eacl/write-schema! (:client env) schema)
    (check-plain-groups! env)
    (doseq [seed [11 29 47]]
      (check-random-walks! env seed))
    (check-eacl-rs-004! env)))
