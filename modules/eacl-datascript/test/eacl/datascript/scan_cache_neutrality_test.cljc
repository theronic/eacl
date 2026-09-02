(ns eacl.datascript.scan-cache-neutrality-test
  "Randomized cache-neutrality differential for the scan-response tiers and
  range reuse on DataScript, on both runtimes: over pseudo-random sparse
  graphs, page sizes, and interleaved supported writes, every public page
  (data, cursors, flags) is identical with the shared tier on, off, the memo
  off, and range reuse off, and the tier-on command multiset is a subset of
  the tier-off one."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.client.range-reuse :as range-reuse]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.scan-cache-fixture :as fixture]))

(defn- commands-of
  [f]
  (let [commands (atom [])]
    (binding [backend/*invoke-observer*
              (fn [{:keys [phase operation]}]
                (when (and (= :after phase)
                           (contains? #{:subject->resources :resource->subjects}
                                      operation))
                  (swap! commands conj operation)))]
      (let [result (f)]
        [result @commands]))))

(defn- public-shape
  [page]
  (-> page
      (select-keys [:data :page-info])
      (update :page-info dissoc :start-cursor :end-cursor)))

(defn- seeded-clients
  [config]
  (let [conn (datascript/create-conn)
        enabled (datascript/make-client conn {})
        disabled (datascript/make-client conn {:scan-cache false})]
    (fixture/seed! enabled config
                   (fn [ids] (ds/transact! conn (mapv (fn [id] {:eacl/id id}) ids))))
    {:conn conn :enabled enabled :disabled disabled}))

(defn- lcg
  "Park-Miller minimal standard; exact on both runtimes."
  [seed]
  (let [state (atom (inc (mod seed 2147483646)))]
    (fn [bound]
      (let [value (mod (* 48271 @state) 2147483647)]
        (reset! state value)
        (mod (quot value 256) bound)))))

(defn- member-groups
  [config u]
  (set (take (:groups-per-user config)
             (get (fixture/memberships config) u))))

(deftest pages-are-identical-under-every-cache-mode-test
  (doseq [seed [3 11 29]]
    (let [config {:users 12 :groups 18 :groups-per-user 5 :seed seed :empty-fraction 0.5}
          {:keys [conn enabled disabled]} (seeded-clients config)
          next-int (lcg (* 7 seed))
          round (fn [label]
                  (testing (str "seed " seed " " label)
                    (doseq [u (range (:users config))
                            n [1 3 7 20]]
                      (let [[on on-commands] (commands-of #(fixture/page enabled u n))
                            [off off-commands] (commands-of #(fixture/page disabled u n :cache? false))
                            memo-off (binding [scan-cache/*memo-disabled?* true]
                                       (fixture/page disabled u n :cache? false))
                            range-off (binding [range-reuse/*disabled?* true]
                                        (fixture/page enabled u n))]
                        (is (= (public-shape off) (public-shape on)))
                        (is (= (public-shape off) (public-shape memo-off)))
                        (is (= (public-shape off) (public-shape range-off)))
                        (is (<= (count on-commands) (count off-commands))
                            "the tier never issues a command the uncached run did not")
                        (when-let [cursor (get-in on [:page-info :end-cursor])]
                          (is (= (public-shape (fixture/page disabled u n :cache? false :after cursor))
                                 (public-shape (fixture/page enabled u n :after cursor)))
                              "continuations from the served cursor agree"))))))]
      (round "before any write")
      ;; A supported write to the scanned relations between rounds: a new
      ;; doc under a group the chosen user does not belong to yet.
      (let [u (next-int (:users config))
            g (first (remove (member-groups config u) (range (:groups config))))
            doc-id (str "doc-w-" seed)]
        (ds/transact! conn [{:eacl/id doc-id}])
        (eacl/create-relationships!
         enabled
         [(eacl/->Relationship (fixture/group g) :group (eacl/spice-object :doc doc-id))
          (eacl/->Relationship (fixture/user u) :member (fixture/group g))]))
      (round "after a relevant write")
      ;; And a deletion of one of another user's existing memberships.
      (let [u (next-int (:users config))
            g (first (sort (member-groups config u)))]
        (eacl/delete-relationships!
         enabled
         [(eacl/->Relationship (fixture/user u) :member (fixture/group g))]))
      (round "after a deletion"))))
