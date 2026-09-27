(ns eacl.wildcard-contract-support
  "Backend-neutral contract for SpiceDB wildcard subjects (`user:*`).

  Every public backend runs `assert-wildcard-contract!` against a fresh
  writable client; JVM clients with the CEL evaluator also run
  `assert-wildcard-caveat-contract!`. Expected subject lookups follow the
  behavior observed on SpiceDB v1.56.0 (formal/fixtures/wildcards), stated as
  EACL's documented listing: every granted subject holding a tuple in the
  permission's relations is listed, and the `*` entry names the subjects the
  wildcard does not grant."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.core :as eacl]))

(defn ->user [id] (eacl/spice-object :user id))
(defn ->team [id] (eacl/spice-object :team id))
(defn ->area [id] (eacl/spice-object :area id))
(defn ->folder [id] (eacl/spice-object :folder id))
(defn ->doc [id] (eacl/spice-object :doc id))

(def wildcard-schema
  "definition user {}
definition team {
  relation member: user | user:*
}
definition area {
  relation viewer: user | user:*
  relation banned: user
  relation allowed: user
  relation editor: user
  relation wild: user:*
  relation guest: team | team:*
  permission view = viewer
  permission enter = viewer - banned
  permission edit = viewer & editor
  permission audit = (viewer - banned) + editor
  permission narrow = (viewer - banned) - (wild - allowed)
  permission both = viewer & wild
  permission nobody = viewer - wild
  permission mingle = guest
}
definition folder {
  relation parent: folder
  relation viewer: user | user:*
  relation banned: user
  permission view = viewer + parent->view
  permission clean_view = view - banned
}
definition doc {
  relation folder: folder
  relation reader: user | user:*
  permission read = reader + folder->view
  permission peek = folder->viewer
  permission read_clean = folder->clean_view
}")

(def objects
  (vec (concat ["alice" "bob" "carol" "dave" "erin" "red" "blue" "a1" "a2" "a3"]
               (map #(str "f" %) (range 4))
               (map #(str "d" %) (range 12)))))

(def relationships
  (vec
   (concat
    [(eacl/->Relationship (->user "*") :viewer (->area "a1"))
     (eacl/->Relationship (->user "bob") :banned (->area "a1"))
     (eacl/->Relationship (->user "bob") :editor (->area "a1"))
     (eacl/->Relationship (->user "carol") :banned (->area "a1"))
     (eacl/->Relationship (->user "carol") :viewer (->area "a1"))
     (eacl/->Relationship (->user "*") :wild (->area "a1"))
     (eacl/->Relationship (->user "carol") :allowed (->area "a1"))
     (eacl/->Relationship (->user "erin") :allowed (->area "a1"))
     (eacl/->Relationship (->user "dave") :editor (->area "a1"))
     (eacl/->Relationship (->user "alice") :viewer (->area "a2"))
     (eacl/->Relationship (->team "*") :guest (->area "a3"))
     ;; f0 is public; f1 inherits from f0 and f2 from f1; f3 is bob's.
     (eacl/->Relationship (->user "*") :viewer (->folder "f0"))
     (eacl/->Relationship (->folder "f0") :parent (->folder "f1"))
     (eacl/->Relationship (->folder "f1") :parent (->folder "f2"))
     (eacl/->Relationship (->user "bob") :viewer (->folder "f3"))
     (eacl/->Relationship (->user "carol") :banned (->folder "f1"))
     (eacl/->Relationship (->user "*") :reader (->doc "d11"))]
    (for [i (range 12)]
      (eacl/->Relationship (->folder (str "f" (mod i 4))) :folder (->doc (str "d" i)))))))

(defn- ids [page] (mapv :id (:data page)))

(defn- walk
  "Every item of a cursor walk, following `:after` end cursors."
  [f query]
  (loop [query query items [] guard 0]
    (let [page (f query)
          items (into items (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< guard 100))
        (recur (assoc query :after (get-in page [:page-info :end-cursor]))
               items (inc guard))
        items))))

(defn subject-entries
  "lookup-subjects as `{id excluded-ids}`, order-insensitive."
  [client resource permission]
  (into {}
        (map (fn [{:keys [id excluded-subjects]}]
               [id (set (map :id excluded-subjects))]))
        (walk #(eacl/lookup-subjects client %)
              {:resource resource :permission permission
               :subject/type :user :first 2})))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(defn- relationship-tuples
  [client query]
  (set (map (fn [{:keys [subject relation resource]}]
              [(:type subject) (:id subject) relation (:type resource) (:id resource)])
            (:data (eacl/read-relationships client query)))))

(defn assert-wildcard-contract!
  "`client` is a fresh writable client whose database holds `objects` as
  entities addressed by their `:eacl/id`."
  [client]
  (testing "a wildcard schema is admitted and read back with its branches"
    (is (map? (eacl/write-schema! client wildcard-schema)))
    (is (some #(and (= :viewer (:eacl.relation/relation-name %))
                    (= :area (:eacl.relation/resource-type %))
                    (true? (:eacl.relation/allows-unqualified-wildcard? %)))
              (:relations (eacl/read-schema client)))))

  (testing "a wildcard cannot be the left side of an arrow"
    (is (= :eacl.schema/expression-resolution-failed
           (:type (error-data
                   #(eacl/write-schema!
                     client
                     "definition user {}
definition folder {
  relation parent: folder | folder:*
  permission view = parent->view
}"))))))

  (eacl/create-relationships! client relationships)

  (testing "writes validate the subject form against the relation"
    (is (= :wildcard-subject-not-allowed
           (:reason (error-data
                     #(eacl/create-relationship!
                       client (->user "*") :banned (->area "a2"))))))
    (is (= :concrete-subject-not-allowed
           (:reason (error-data
                     #(eacl/create-relationship!
                       client (->user "alice") :wild (->area "a2"))))))
    (is (= :eacl/wildcard-not-allowed
           (:type (error-data
                   #(eacl/create-relationship!
                     client (->user "alice") :viewer (->area "*")))))))

  (testing "the wildcard ID is rejected where SpiceDB rejects it"
    (doseq [[label f]
            [[:can? #(eacl/can? client (->user "*") :view (->area "a1"))]
             [:check-resource #(eacl/can? client (->user "alice") :view (->area "*"))]
             [:lookup-resources #(eacl/lookup-resources
                                  client {:subject (->user "*") :permission :view
                                          :resource/type :area})]
             [:count-resources #(eacl/count-resources
                                 client {:subject (->user "*") :permission :view
                                         :resource/type :area})]
             [:lookup-subjects #(eacl/lookup-subjects
                                 client {:resource (->area "*") :permission :view
                                         :subject/type :user})]
             [:count-subjects #(eacl/count-subjects
                                client {:resource (->area "*") :permission :view
                                        :subject/type :user})]]]
      (testing (str label)
        (is (= :eacl/wildcard-not-allowed (:type (error-data f)))))))

  (testing "checks grant every subject through a wildcard, across operators"
    (is (true? (eacl/can? client (->user "alice") :view (->area "a1"))))
    (is (false? (eacl/can? client (->user "unknown-user") :view (->area "a1")))
        "the wildcard grants existing subjects; an unknown ID matches nothing")
    (is (true? (eacl/can? client (->user "alice") :enter (->area "a1"))))
    (is (false? (eacl/can? client (->user "bob") :enter (->area "a1"))))
    (is (true? (eacl/can? client (->user "dave") :edit (->area "a1"))))
    (is (false? (eacl/can? client (->user "alice") :edit (->area "a1"))))
    (is (true? (eacl/can? client (->user "erin") :narrow (->area "a1"))))
    (is (false? (eacl/can? client (->user "alice") :narrow (->area "a1"))))
    (is (true? (eacl/can? client (->user "alice") :both (->area "a1"))))
    (is (false? (eacl/can? client (->user "alice") :nobody (->area "a1"))))
    (is (false? (eacl/can? client (->user "alice") :view (->area "a3"))))
    (is (true? (eacl/can? client (->team "red") :mingle (->area "a3"))))
    (testing "arrows reach relations and permissions that hold a wildcard"
      (is (true? (eacl/can? client (->user "alice") :read (->doc "d2"))))
      (is (false? (eacl/can? client (->user "alice") :read (->doc "d3"))))
      (is (true? (eacl/can? client (->user "bob") :read (->doc "d3"))))
      (is (true? (eacl/can? client (->user "alice") :peek (->doc "d0"))))
      (is (false? (eacl/can? client (->user "alice") :peek (->doc "d1"))))
      (is (false? (eacl/can? client (->user "carol") :read_clean (->doc "d1"))))
      (is (true? (eacl/can? client (->user "carol") :read_clean (->doc "d2"))))))

  (testing "lookup-resources and counts include wildcard grants"
    (is (= #{"a1" "a2"}
           (set (ids (eacl/lookup-resources
                      client {:subject (->user "alice") :permission :view
                              :resource/type :area})))))
    (is (= #{"a1"}
           (set (ids (eacl/lookup-resources
                      client {:subject (->user "erin") :permission :narrow
                              :resource/type :area})))))
    (let [expected #{"d0" "d1" "d2" "d4" "d5" "d6" "d8" "d9" "d10" "d11"}
          walked (walk #(eacl/lookup-resources client %)
                       {:subject (->user "alice") :permission :read
                        :resource/type :doc :first 3})]
      (is (= expected (set (map :id walked))))
      (is (= (count expected) (count walked)) "cursor walks do not repeat")
      (is (= (count expected)
             (:count (eacl/count-resources
                      client {:subject (->user "alice") :permission :read
                              :resource/type :doc})))))
    (is (= #{"d0" "d4" "d8" "d2" "d6" "d10"}
           (set (map :id (walk #(eacl/lookup-resources client %)
                               {:subject (->user "carol") :permission :read_clean
                                :resource/type :doc :first 2}))))))

  (testing "lookup-subjects returns the wildcard and its exclusions"
    (is (= {"carol" #{} "*" #{}} (subject-entries client (->area "a1") :view)))
    (is (= {"*" #{"bob" "carol"}} (subject-entries client (->area "a1") :enter)))
    (is (= {"bob" #{} "dave" #{} "*" #{"carol"}}
           (subject-entries client (->area "a1") :audit)))
    (is (= {"erin" #{}} (subject-entries client (->area "a1") :narrow)))
    (is (= {"carol" #{} "*" #{}} (subject-entries client (->area "a1") :both)))
    (is (= {} (subject-entries client (->area "a1") :nobody)))
    (is (= {"bob" #{} "dave" #{}} (subject-entries client (->area "a1") :edit)))
    (is (= {"*" #{}} (subject-entries client (->doc "d2") :read)))
    (is (= {"*" #{"carol"}} (subject-entries client (->doc "d1") :read_clean)))
    (is (= {"alice" #{}} (subject-entries client (->area "a2") :view)))
    (testing "count-subjects counts entries: the wildcard once"
      (is (= 2 (:count (eacl/count-subjects
                        client {:resource (->area "a1") :permission :view
                                :subject/type :user}))))
      (is (= 3 (:count (eacl/count-subjects
                        client {:resource (->area "a1") :permission :audit
                                :subject/type :user}))))
      (is (= 1 (:count (eacl/count-subjects
                        client {:resource (->doc "d1") :permission :read_clean
                                :subject/type :user}))))))

  (testing "relationship reads treat * as the literal wildcard subject"
    (is (= #{[:user "*" :viewer :area "a1"] [:user "*" :wild :area "a1"]
             [:user "*" :viewer :folder "f0"] [:user "*" :reader :doc "d11"]}
           (relationship-tuples client {:subject/type :user :subject/id "*"})))
    (is (= #{[:team "*" :guest :area "a3"]}
           (relationship-tuples client {:subject/type :team :subject/id "*"}))))

  (testing "writes invalidate cached wildcard answers"
    (is (true? (eacl/can? client (->user "alice") :read (->doc "d11"))))
    (eacl/delete-relationship! client (->user "*") :reader (->doc "d11"))
    (is (false? (eacl/can? client (->user "alice") :read (->doc "d11"))))
    (eacl/create-relationship! client (->user "*") :reader (->doc "d11"))
    (is (true? (eacl/can? client (->user "alice") :read (->doc "d11")))))

  (testing "schema replacement keeps stored wildcard relationships valid"
    (is (= :eacl.schema/relationship-qualifier-in-use
           (:type (error-data
                   #(eacl/write-schema!
                     client
                     (str/replace wildcard-schema
                                  "relation reader: user | user:*"
                                  "relation reader: user")))))))

  (testing "delete-object! removes wildcard relationships"
    (eacl/delete-object! client (->doc "d11"))
    (is (false? (eacl/can? client (->user "alice") :read (->doc "d11"))))
    (is (not (contains? (relationship-tuples client {:subject/type :user :subject/id "*"})
                        [:user "*" :reader :doc "d11"])))
    (eacl/delete-object! client (->user "*"))
    (is (= #{} (relationship-tuples client {:subject/type :user :subject/id "*"})))
    (is (false? (eacl/can? client (->user "alice") :view (->area "a1"))))
    (is (= #{[:team "*" :guest :area "a3"]}
           (relationship-tuples client {:subject/type :team :subject/id "*"}))
        "deleting user:* keeps every other type's wildcard")
    (is (true? (eacl/can? client (->team "blue") :mingle (->area "a3"))))))

(def caveat-schema
  "The first consumer's schema (the author's blog)."
  "caveat nothing_sensitive(carrying list<string>) { !(\"launch-codes\" in carrying) && !(\"customer-list\" in carrying) }
definition user {}
definition area {
  relation anyone: user:* with nothing_sensitive
  relation viewer: user:*
  relation banned: user
  permission exit = anyone
  permission stroll = viewer - banned
}")

(defn assert-wildcard-caveat-contract!
  "`client` needs a CEL evaluator and a controllable `:clock` backed by
  `now-ms` (an atom of epoch milliseconds). Its database holds `objects`."
  [client now-ms]
  (eacl/write-schema! client caveat-schema)
  (eacl/write-relationships!
   client
   [{:operation :create
     :relationship (assoc (eacl/->Relationship (->user "*") :anyone (->area "a1"))
                          :caveat "nothing_sensitive")}
    {:operation :create
     :relationship (assoc (eacl/->Relationship (->user "*") :viewer (->area "a2"))
                          :valid-until-ms (+ @now-ms 1000))}])

  (testing "a Caveated wildcard branch requires its Caveat"
    (is (= :caveat-not-allowed
           (:reason (error-data
                     #(eacl/create-relationship!
                       client (->user "*") :anyone (->area "a2")))))))

  (testing "a wildcard Caveat is evaluated for every subject"
    (let [exit (fn [carrying]
                 (eacl/check-permission
                  client {:subject (->user "alice") :permission :exit
                          :resource (->area "a1")
                          :caveat-context {"carrying" carrying}}))]
      (is (= :has-permission (:permissionship (exit ["lunch"]))))
      (is (= :no-permission (:permissionship (exit ["launch-codes"]))))
      (is (= :no-permission (:permissionship (exit ["badge" "customer-list"])))))
    (is (= ["a1"]
           (ids (eacl/lookup-resources
                 client {:subject (->user "bob") :permission :exit
                         :resource/type :area
                         :caveat-context {"carrying" []}}))))
    (is (= [] (ids (eacl/lookup-resources
                    client {:subject (->user "bob") :permission :exit
                            :resource/type :area
                            :caveat-context {"carrying" ["launch-codes"]}}))))
    (is (= ["*"] (ids (eacl/lookup-subjects
                       client {:resource (->area "a1") :permission :exit
                               :subject/type :user
                               :caveat-context {"carrying" []}}))))
    (is (= [] (ids (eacl/lookup-subjects
                    client {:resource (->area "a1") :permission :exit
                            :subject/type :user
                            :caveat-context {"carrying" ["launch-codes"]}})))))

  (testing "an expiring wildcard relationship stops granting at its deadline"
    (is (true? (eacl/can? client (->user "alice") :stroll (->area "a2"))))
    (swap! now-ms + 1000)
    (is (false? (eacl/can? client (->user "alice") :stroll (->area "a2"))))
    (is (= [] (ids (eacl/lookup-subjects
                    client {:resource (->area "a2") :permission :stroll
                            :subject/type :user}))))))
