(ns eacl.operator-engine.wildcard-anchor-contract
  "Backend-neutral contract: listing or counting the resources of an
  intersection costs what the subject's own relationships cost, however many
  resources an operand that a wildcard grants to every subject reaches.

  Every fixture has `total` ledgers owned by one user, each open to every
  user through a wildcard (`user:*`) relationship, and a second user who owns
  five of them. Listing the second user's open ledgers must read the same
  relationships at every `total`, whatever the wildcard operand is called,
  wherever it stands in the intersection, and whether the wildcard relation
  is reached through arrows or sits on the ledger itself.

  The caller supplies `new-store`: a function of client options returning a
  client over an empty store and an `add-objects!` that makes object ids
  resolvable."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.engine.v8 :as engine]))

(defn- subscription-schema
  "`gate` names the permission that follows the subscription to its wildcard
  relation; `open` is the intersection."
  [gate open]
  (str "definition user {}
definition subscription {
  relation everyone: user:*
}
definition ledger {
  relation owner: user
  relation subscription: subscription
  permission view = owner
  permission " gate " = subscription->everyone
  permission open = " open "
}"))

(defn- direct-schema
  [declaration open]
  (str "definition user {}
definition ledger {
  relation owner: user
  relation subscriber: " declaration "
  permission view = owner
  permission open = " open "
}"))

(def schemas
  "Each fixture's schema and how a ledger is opened to every user. `:through`
  names the objects between the ledger and the wildcard relationship."
  {;; The operand's name sorts before `view`, in both operand orders.
   :named {:schema (subscription-schema "subscribed" "view & subscribed")
           :through :subscription}
   :named-swapped {:schema (subscription-schema "subscribed" "subscribed & view")
                   :through :subscription}
   ;; The operand's name sorts after `view`, in both operand orders.
   :renamed {:schema (subscription-schema "zsubscribed" "view & zsubscribed")
             :through :subscription}
   :renamed-swapped {:schema (subscription-schema "zsubscribed" "zsubscribed & view")
                     :through :subscription}
   ;; The arrow written in the intersection.
   :inline {:schema (subscription-schema "subscribed" "view & subscription->everyone")
            :through :subscription}
   ;; The wildcard relation on the ledger itself.
   :direct {:schema (direct-schema "user:*" "view & subscriber")
            :through :ledger}
   :direct-swapped {:schema (direct-schema "user:*" "subscriber & view")
                    :through :ledger}
   :direct-mixed {:schema (direct-schema "user | user:*" "view & subscriber")
                  :through :ledger}
   ;; Two arrows to the wildcard: a subscription to one of three plans.
   :plan {:schema "definition user {}
definition plan {
  relation subscriber: user:*
}
definition subscription {
  relation plan: plan
  permission subscriber = plan->subscriber
}
definition ledger {
  relation owner: user
  relation subscription: subscription
  permission view = owner
  permission subscribed = subscription->subscriber
  permission open = view & subscribed
}"
          :through :plan}})

(def ^:private plans ["basic" "pro" "business"])
(def owned 5)

(defn- user [id] (eacl/spice-object :user id))
(defn- ledger [id] (eacl/spice-object :ledger id))
(defn- subscription [id] (eacl/spice-object :subscription id))
(defn- plan [id] (eacl/spice-object :plan id))

(defn- ledger-id [index] (str "ledger-" index))

(defn owned-indexes
  "The five ledgers of the second owner, spread over the platform."
  [total]
  (mapv #(quot (* % total) owned) (range owned)))

(defn- relationships
  [through total]
  (let [mine (set (owned-indexes total))]
    (concat
     (when (= :plan through)
       (map #(eacl/->Relationship (user "*") :subscriber (plan %)) plans))
     (mapcat
      (fn [index]
        (let [resource (ledger (ledger-id index))
              paid (subscription (str "subscription-" index))]
          (concat
           [(eacl/->Relationship (user "platform") :owner resource)]
           (when (contains? mine index)
             [(eacl/->Relationship (user "alice") :owner resource)])
           (case through
             :ledger [(eacl/->Relationship (user "*") :subscriber resource)]
             :subscription [(eacl/->Relationship paid :subscription resource)
                            (eacl/->Relationship (user "*") :everyone paid)]
             :plan [(eacl/->Relationship paid :subscription resource)
                    (eacl/->Relationship (plan (nth plans (mod index (count plans))))
                                         :plan paid)]))))
      (range total)))))

(def ^:private work-meters
  "The request meters that count reads of relationship data."
  [:adapter-reads :commands :fetched-values :probes :candidates-examined])

(defn build!
  "A fresh store holding the fixture `fixture` at `total` ledgers. Returns its
  client, which keeps no shared cache, and `meters`, an atom holding the work
  meters of the client's last request."
  [new-store fixture total]
  (let [{:keys [schema through]} (get schemas fixture)
        meters (atom nil)
        {:keys [client add-objects!]}
        (new-store {:cache cache/no-cache
                    :io-observer #(reset! meters (select-keys (:meters %) work-meters))})]
    (add-objects! (concat ["platform" "alice" "nobody"]
                          plans
                          (map ledger-id (range total))
                          (map #(str "subscription-" %) (range total))))
    (eacl/write-schema! client schema)
    (doseq [chunk (partition-all 512 (relationships through total))]
      (eacl/create-relationships! client (vec chunk)))
    {:client client :meters meters}))

(defn- open-query [subject]
  {:subject (user subject) :permission :open :resource/type :ledger})

(defn- measure
  "The result of one request `f` and the work it reported: the request's
  meters and the engine's traversal counters."
  [meters f]
  (let [stats (atom {})
        result (binding [engine/*recursive-traversal-stats* stats] (f))]
    {:result result
     :work {:meters @meters
            :traversal (select-keys @stats [:fetched-values :advanced-datoms :stream-opens])}}))

(defn- walk
  "Every item of a forward cursor walk in pages of `page-size`."
  [client query page-size]
  (loop [after nil pages 0 items []]
    (let [page (eacl/lookup-resources
                client (cond-> (assoc query :first page-size) after (assoc :after after)))
          items (into items (map :id) (:data page))]
      (when (> pages 2000)
        (throw (ex-info "Walk did not terminate." {:query query})))
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) (inc pages) items)
        items))))

(defn- walk-from-the-end
  "Every item of a backward cursor walk in pages of `page-size`, in the
  listing's order."
  [client query page-size]
  (loop [before nil pages 0 items ()]
    (let [page (eacl/lookup-resources
                client (cond-> (assoc query :last page-size) before (assoc :before before)))
          items (concat (map :id (:data page)) items)]
      (when (> pages 2000)
        (throw (ex-info "Walk did not terminate." {:query query})))
      (if (get-in page [:page-info :has-previous-page?])
        (recur (get-in page [:page-info :start-cursor]) (inc pages) items)
        (vec items)))))

(defn- count-with-limit [client subject limit]
  (select-keys (eacl/count-resources client (assoc (open-query subject) :count-limit limit))
               [:count :truncated?]))

(defn- observations
  "One subject's listing and counts of `open`, each with its work."
  [{:keys [client meters]} subject]
  ;; The first request seals the plan; it is not measured.
  (eacl/can? client (user subject) :open (ledger (ledger-id 0)))
  {:listing (measure meters #(mapv :id (:data (eacl/lookup-resources
                                               client (assoc (open-query subject) :first 100)))))
   :count (measure meters #(:count (eacl/count-resources client (open-query subject))))
   :bounded-count (measure meters #(count-with-limit client subject 3))
   :covering-count (measure meters #(count-with-limit client subject 9))})

(defn assert-listing-work-is-independent-of-the-platform!
  "The second owner's listing and counts are right at `small` and `large`
  ledgers and report identical engine work at both."
  [new-store fixture small large]
  (testing (str fixture)
    (let [[at-small at-large]
          (mapv (fn [total]
                  (let [observed (observations (build! new-store fixture total) "alice")]
                    (testing (str total " ledgers")
                      (is (= (set (map ledger-id (owned-indexes total)))
                             (set (get-in observed [:listing :result])))
                          "lookup-resources lists the subject's own open ledgers")
                      (is (= owned (count (get-in observed [:listing :result])))
                          "each once")
                      (is (= owned (get-in observed [:count :result]))
                          "count-resources")
                      (is (= {:count 3 :truncated? true}
                             (get-in observed [:bounded-count :result]))
                          "count-resources with a limit below the count")
                      (is (= {:count owned :truncated? false}
                             (get-in observed [:covering-count :result]))
                          "count-resources with a limit above the count"))
                    observed))
                [small large])]
      (doseq [operation [:listing :count :bounded-count :covering-count]]
        (is (= (get-in at-small [operation :work])
               (get-in at-large [operation :work]))
            (str operation " reads the same relationships at " small " and " large
                 " ledgers"))))))

(defn assert-listing-succeeds-under-the-default-limits!
  "At `total` ledgers the second owner's listing and counts still answer."
  [new-store fixture total]
  (testing (str fixture " at " total " ledgers")
    (let [{:keys [client]} (build! new-store fixture total)
          expected (set (map ledger-id (owned-indexes total)))]
      (is (= expected
             (set (mapv :id (:data (eacl/lookup-resources
                                    client (assoc (open-query "alice") :first 100)))))))
      (is (= owned (:count (eacl/count-resources client (open-query "alice")))))
      (is (= {:count 3 :truncated? true} (count-with-limit client "alice" 3)))
      (is (= {:count owned :truncated? false} (count-with-limit client "alice" 9))))))

(defn assert-answers!
  "Pages, checks and subject listings of `open` at `total` ledgers."
  [new-store fixture total]
  (testing (str fixture)
    (let [{:keys [client]} (build! new-store fixture total)
          mine (set (map ledger-id (owned-indexes total)))
          everything (set (map ledger-id (range total)))]
      (testing "pages return every item once, in one order"
        (doseq [[subject expected] [["alice" mine] ["platform" everything] ["nobody" #{}]]]
          (let [whole (walk client (open-query subject) (+ total 10))]
            (is (= expected (set whole)) subject)
            (is (= (count expected) (count whole)) (str subject ": no item twice"))
            (doseq [page-size [1 2 7]]
              (is (= whole (walk client (open-query subject) page-size))
                  (str subject ": pages of " page-size " follow the listing's order"))
              (is (= whole (walk-from-the-end client (open-query subject) page-size))
                  (str subject ": pages of " page-size " from the end follow it too"))))))
      (testing "counts"
        (is (= total (:count (eacl/count-resources client (open-query "platform")))))
        (is (zero? (:count (eacl/count-resources client (open-query "nobody"))))))
      (testing "checks"
        (doseq [index (range total)
                :let [resource (ledger (ledger-id index))]]
          (is (= (contains? mine (ledger-id index))
                 (eacl/can? client (user "alice") :open resource))
              (str "alice " index))
          (is (true? (eacl/can? client (user "platform") :open resource)))
          (is (false? (eacl/can? client (user "nobody") :open resource)))))
      (testing "subjects"
        (doseq [index (range total)
                :let [query {:resource (ledger (ledger-id index)) :permission :open
                             :subject/type :user}
                      expected (cond-> #{"platform"}
                                 (contains? mine (ledger-id index)) (conj "alice"))]]
          (is (= expected (set (map :id (:data (eacl/lookup-subjects
                                                client (assoc query :first 10))))))
              (str "lookup-subjects " index))
          (is (= (count expected) (:count (eacl/count-subjects client query)))
              (str "count-subjects " index)))))))

(def typed-anchor-schema
  "`open` generates from its exclusion, which no wildcard reaches. `banned`
  declares agents alone, so the exclusion has one relation scan per operand
  for agents and none for users."
  "definition user {}
definition agent {}
definition ledger {
  relation viewer: user | agent
  relation banned: agent
  relation subscriber: user:* | agent:*
  permission open = subscriber & (viewer - banned)
}")

(defn assert-a-typed-anchor-answers-for-every-subject-type!
  "A user's and an agent's listings, counts, checks and subject listings of
  `open` when its anchor serves one of the two subject types through a direct
  specialization. Generating from the wildcard relation never met the
  exclusion's specialization; generating from the exclusion did, and a lookup
  for users failed with `:eacl.operator/invalid-seekable-plan`."
  [new-store]
  (let [{:keys [client add-objects!]} (new-store {:cache cache/no-cache})
        agent #(eacl/spice-object :agent %)
        ledgers (mapv #(str "ledger-" %) (range 6))
        on (fn [subject relation indexes]
             (map #(eacl/->Relationship subject relation (ledger (nth ledgers %))) indexes))
        expected {[:user "alice"] #{"ledger-0" "ledger-1"}
                  [:agent "robot"] #{"ledger-0" "ledger-2"}}]
    (add-objects! (concat ["alice" "robot"] ledgers))
    (eacl/write-schema! client typed-anchor-schema)
    (eacl/create-relationships!
     client
     (vec (concat (on (user "alice") :viewer [0 1 2])
                  (on (agent "robot") :viewer [0 1 2 3])
                  (on (agent "robot") :banned [1])
                  (on (user "*") :subscriber [0 1 4])
                  (on (agent "*") :subscriber [0 1 2 5]))))
    (doseq [[[type id] mine] expected
            :let [subject (eacl/spice-object type id)
                  query {:subject subject :permission :open :resource/type :ledger}]]
      (testing (str (name type) " " id)
        (let [whole (walk client query 10)]
          (is (= mine (set whole)) "lookup-resources")
          (is (= (count mine) (count whole)) "each once")
          (is (= whole (walk client query 1)) "pages of 1 follow the listing's order"))
        (is (= (count mine) (:count (eacl/count-resources client query))) "count-resources")
        (is (= {:count 1 :truncated? true}
               (select-keys (eacl/count-resources client (assoc query :count-limit 1))
                            [:count :truncated?]))
            "count-resources with a limit below the count")
        (doseq [resource ledgers]
          (is (= (contains? mine resource) (eacl/can? client subject :open (ledger resource)))
              (str "check " resource))
          (let [subjects {:resource (ledger resource) :permission :open :subject/type type}]
            (is (= (if (contains? mine resource) [id] [])
                   (mapv :id (:data (eacl/lookup-subjects client (assoc subjects :first 10)))))
                (str "lookup-subjects " resource))
            (is (= (if (contains? mine resource) 1 0)
                   (:count (eacl/count-subjects client subjects)))
                (str "count-subjects " resource))))))))

(def recursive-anchor-schema
  "`open` generates from `view`, which recurses through `parent`."
  "definition user {}
definition folder {
  relation parent: folder
  relation owner: user
  relation subscriber: user:*
  permission view = owner + parent->view
  permission open = view & subscriber
}")

(defn assert-a-last-page-of-a-recursive-anchor-needs-complete-evaluation!
  "A page counted from the end of a recursive generator exhausts it, which a
  request opts into with `:evaluation :complete-denotation`. `open` now
  generates from the recursive `view`, so its last page asks for that like
  `view`'s own; generating from the wildcard relation it answered without."
  [new-store]
  (let [{:keys [client add-objects!]} (new-store {:cache cache/no-cache})
        folder #(eacl/spice-object :folder %)
        folders (mapv #(str "folder-" %) (range 6))
        query (fn [permission]
                {:subject (user "alice") :permission permission :resource/type :folder})
        refusal (fn [request]
                  (try (eacl/lookup-resources client request)
                       nil
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                         (:eacl/error (ex-data error)))))]
    (add-objects! (concat ["alice" "platform"] folders))
    (eacl/write-schema! client recursive-anchor-schema)
    (eacl/create-relationships!
     client
     (vec (concat
           [(eacl/->Relationship (user "alice") :owner (folder "folder-0"))
            (eacl/->Relationship (user "platform") :owner (folder "folder-5"))]
           (map #(eacl/->Relationship (folder "folder-0") :parent (folder %))
                (subvec folders 1 5))
           (map #(eacl/->Relationship (user "*") :subscriber (folder %)) folders))))
    (let [whole (walk client (query :open) 10)]
      (is (= (set (subvec folders 0 5)) (set whole)))
      (is (= 5 (count whole)))
      (is (= whole (walk client (query :open) 2)))
      (doseq [permission [:open :view]]
        (is (= :eacl.pagination/complete-evaluation-required
               (refusal (assoc (query permission) :last 2)))
            (str permission ": a last page without the opt-in is refused")))
      (is (= whole
             (walk-from-the-end client (assoc (query :open) :evaluation :complete-denotation) 2))
          "with the opt-in, pages from the end follow the listing's order"))))
