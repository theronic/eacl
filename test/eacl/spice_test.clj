(ns eacl.spice-test
  (:require [clojure.string :as string]
            [clojure.test :as t :refer [deftest testing is use-fixtures]]
            [eacl.core :as eacl :refer [spice-object ->Relationship]]
            [eacl.datomic.schema :as schema]
            [datomic.api :as d]                             ; ideally don't want this
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->team ->server ->platform ->vpc ->account]]
            [eacl.datomic.core :as spiceomic]
            [clojure.tools.logging :as log]
            [eacl.spicedb.consistency :as consistency :refer [fully-consistent]]))

(deftest spicedb-tests

  (with-mem-conn [conn schema/v5-schema]

    (testing "spice-object takes [type id ?relation] and yields a SpiceObject with support for subject_relation"
      (is (= #eacl.core.SpiceObject{:type :user, :id "my-user", :relation nil}
             (spice-object :user "my-user")))
      (is (= #eacl.core.SpiceObject{:type :team, :id "dev-team", :relation :member}
             (spice-object :team "dev-team" :member))))

    (testing "->user means (partial spice-object :user). Creates a SpiceObject record with {:keys [type id relation]}"
      (def my-user (->user "ben"))
      (def joe's-user (->user "joe"))
      (def super-user (->user "andre"))
      (def new-joiner (->user "new-joiner"))

      (testing "We treat subject & resource types as keywords in Clojure-land."
        (is (= :user (:type my-user)))
        (is (= "ben" (:id my-user)))))

    (testing "To define a SubjectReference with a :relation (subject_relation), pass another arg to spice-object"
      (let [team-member (->team "my-team" :member)]
        (is (= :team (:type team-member)))
        (is (= "my-team" (:id team-member)))
        (is (= :member (:relation team-member)))))

    (testing "The platform definition is an abstraction to support super administrators"
      (def ca-platform (->platform "cloudafrica")))

    (testing "Define some server fixtures (we coerce numeric IDs to strings, but not back yet)"
      (def my-server (->server "123"))
      (def my-other-server (->server "456"))
      (def joe's-server (->server "not-my-server")))

    (testing "Two account fixtures"
      (def my-account (->account "operativa"))
      (def acme-account (->account "acme")))

    (testing "Make a Spice client and hold onto channel for disposal later."
      (let [client (spiceomic/make-client conn {})]
        (is client)
        ;(is (satisfies? IAuthorization client))
        ; use :spicedb/client Integrant key instead of :permissions/spicedb because we want to migrate Spice schema manually in these tests.
        ;"ensure channel is a managed gRPC channel"
        ;(is (= io.grpc.internal.ManagedChannelOrphanWrapper (class channel)))

        ; def *client REPL testing convenience and fewer parens.
        (def *client client)))

    @(d/transact conn fixtures/base-fixtures)               ; temp until write-relationships

    ;(is (= [] (eacl/read-relationships *client {:resource/type :vpc})))
    ;(is (= [] (eacl/read-relationships *client {:resource/type :account})))
    ;(is (= [] (eacl/read-relationships *client {:resource/type :server})))

    ;(is (= [] (d/q '{:find  [(pull ?relationship [* {:eacl.relationship/resource [*]
    ;                                                 :eacl.relationship/subject [*]}])] ; ?resource-type ?resource ?relation-name ?subject],
    ;                 ;:keys  [:resource/type :resource/id :resource/relation :subject/id],
    ;                 :in    [$ ?subject-type],
    ;                 :where [[?relationship :eacl.relationship/resource ?resource]
    ;                         ;[?resource :resource/type :server] ;?resource-type]
    ;                         [?relationship :eacl.relationship/relation-name ?relation-name]
    ;                         [?relationship :eacl.relationship/subject ?subject]
    ;                         [?subject :resource/type ?subject-type]]}
    ;               (d/db conn) :server)))

    (testing "Clean up prior relationships if non-nil schema (for REPL testing)"
      ; conditional due to Spice segfault when using in-memory datastore under certain conditions.
      (let [?schema-string (try (eacl/read-schema *client) (catch Exception ex nil))]
        (log/debug "schema:" ?schema-string)
        (when true                                          ;?schema-string
          (testing "Clean up any previous :team resource-type relationships."
            (try
              (->> (eacl/read-relationships *client {:resource/type :team
                                                     :consistency   fully-consistent})
                   (eacl/delete-relationships! *client))
              (catch Exception _ex)))

          (testing "Try to clear out any prior :platform relationships. This would be destructive if connected to prod."
            (try
              (->> (eacl/read-relationships *client {:resource/type :platform
                                                     :consistency   fully-consistent})
                   (eacl/delete-relationships! *client))
              (catch Exception _)))

          (testing "Try to clear out any prior :vpc relationships. This would be destructive if connected to prod."
            (try
              (->> (eacl/read-relationships *client {:resource/type :vpc
                                                     :consistency   fully-consistent})
                   (eacl/delete-relationships! *client))
              (catch Exception _)))

          (testing "Clean up any previous :account resource-type relationships."
            (try
              (->> (eacl/read-relationships *client {:resource/type :account
                                                     :consistency   fully-consistent})
                   (eacl/delete-relationships! *client))
              (catch Exception _)))

          (testing "Clean up any previous :server resource-type relationships."
            (is (->> (eacl/read-relationships *client {:resource/type :server
                                                       :consistency   fully-consistent})
                     (eacl/delete-relationships! *client)))))))
    ;(try
    ;  (->> (eacl/read-relationships *client {:resource/type :server
    ;                                          :consistency   fully-consistent})
    ;       (eacl/delete-relationships! *client))
    ;  (catch Exception _))))))

    #_(testing "Migrate schema via IG – will throw if new schema would orphan any relationships"
        (let [{:spicedb/keys [migrate]} (ig/init (igc/integrant-definition) [:spicedb/migrate])]
          ;(testing ":spicedb/migrate returns a WriteSchemaResponse, which is result of eacl/write-schema!"
          ;  (is (= com.authzed.api.v1.SchemaServiceOuterClass$WriteSchemaResponse (class migrate))))
          (testing "Ensure we can read a valid Spice schema after :spicedb/migrate"
            (let [spice-schema-string (try (eacl/read-schema *client) (catch Exception ex ""))]
              (is (false? (string/blank? spice-schema-string)))
              (testing "Spice schema string contains at least one Spice `definition`"
                (is (re-find #"definition" spice-schema-string)))))))

    (testing "After deletion, read-relationships should an return empty seq for :server resources."
      (is (= [] (eacl/read-relationships *client {:resource/type :server}))))

    (testing "Given an empty permission system, ensure no one can reboot my-server"
      ; We specify consistency/fully-consistent because permissions are cached and can? defaults to minimize-latency.
      (is (false? (eacl/can? *client my-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client joe's-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client new-joiner :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client super-user :reboot my-server fully-consistent))))

    (testing "transact the entities with :eacl/type & :eacl/id we are about to use so [:eacl/id 'ben'] resolves"
      (is @(d/transact conn (for [ent [ca-platform
                                       my-account acme-account
                                       super-user my-user joe's-user
                                       my-server my-other-server joe's-server]]
                              {:eacl/id   (:id ent)}))))

    (testing "Write relationships so my-user is the :owner of my-account and my-server is in my-account. Note the order of subjects vs. resources in ->Relationship."
      (let [{:as response, token :zed/token}
            (eacl/create-relationships! *client
                                        [(->Relationship my-user :owner my-account)
                                         (->Relationship my-account :account my-server)])]
        (testing "All Spice operations returns a ZedToken that can be passed to subsequent read operations to guarantee consistent cache."
          (is (string? token))
          (testing "passing anything but consistency/fully-consistent throws until we have a cache to support consistency/fresh."
            (is (thrown? Throwable (eacl/can? *client my-user :reboot my-server (consistency/fresh token))))))))

    (testing "assign joe as the owner of acme-account and joe's server to acme-account"
      (is (eacl/create-relationships! *client
                                      [(->Relationship joe's-user :owner acme-account)
                                       (->Relationship acme-account :account joe's-server)])))

    (testing "Now, my-user can :reboot my-server but joe's-user cannot :reboot my-server"
      (is (true? (eacl/can? *client my-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client joe's-user :reboot my-server fully-consistent))))

    (testing "Query `lookup-resources` to enumerate the resources of a given type (:server) a user can :reboot"

      ; specifying missing subject does not throw an exception, or should it?
      ; this throws:
      ;(is (eacl/lookup-resources *client {:subject       (->user "missing-subject") ;eacl/fully-consistent
      ;                                    :permission    :reboot
      ;                                    :resource/type :server
      ;                                    :consistency   :fully-consistent}))

      ; We need to coerce local resource :id to a string because IDs are read back as strings until we decide if numeric coercion is desirable.
      (is (= [my-server] (->> (eacl/lookup-resources *client
                                                     {:subject       my-user
                                                      :permission    :reboot
                                                      :resource/type :server
                                                      :cursor        nil
                                                      :consistency   fully-consistent})
                              (:data))))

      (is (= [joe's-server] (->> (eacl/lookup-resources *client
                                                        {:resource/type :server
                                                         :permission    :reboot
                                                         :subject       joe's-user
                                                         :consistency   fully-consistent})
                                 (:data)))))

    (testing "We can use `lookup-subjects` to enumerate the subjects (users) who can :reboot servers:"
      ; We coerce local resource :id to string because reads come back as strings. Need to decide if coercion is desirable.
      (is (= [my-user] (:data (eacl/lookup-subjects *client {:resource     my-server
                                                             :permission   :reboot
                                                             :subject/type :user
                                                             :consistency  fully-consistent}))))

      (is (= [joe's-user] (:data (eacl/lookup-subjects *client {:resource     joe's-server
                                                                :permission   :reboot
                                                                :subject/type :user
                                                                :consistency  fully-consistent})))))

    (testing "joe's-user can :reboot their server, but I cannot:"
      (is (true? (eacl/can? *client joe's-user :reboot joe's-server fully-consistent)))
      (is (false? (eacl/can? *client my-user :reboot joe's-server fully-consistent))))

    (testing "Permissions do not extend to my-other-server despite falling under same account, because I don't own it yet."
      (is (false? (eacl/can? *client my-user :reboot my-other-server fully-consistent))))

    (testing "Make super-user a :super_admin of the platform so they can do everything."
      (is (false? (eacl/can? *client super-user :reboot my-server fully-consistent)))
      (is (eacl/create-relationship! *client (->Relationship super-user :super_admin ca-platform))))

    (testing "Super administrators for a given platform, can only control account resources that have a corresponding :platform relation."
      (is (false? (eacl/can? *client super-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client super-user :reboot my-other-server fully-consistent)))
      (is (false? (eacl/can? *client super-user :reboot joe's-server fully-consistent))))

    (testing "Now we assign both accounts to the ca-platform"
      ; The order of subject/resource seems counter-intuitive, but consider that :platform
      ; is a relation under my-account (the resource), so the platform is the subject.
      (is (eacl/create-relationships! *client
                                      [(->Relationship ca-platform :platform my-account)
                                       (->Relationship ca-platform :platform acme-account)])))

    (testing "ensure my-other-server also falls under my-account"
      (is (eacl/create-relationship! *client my-account :account my-other-server)))

    (testing "Super administrators can :reboot all servers because both accounts now fall belong to ca-platform:"
      (is (true? (eacl/can? *client super-user :reboot my-server fully-consistent)))
      (is (true? (eacl/can? *client super-user :reboot my-other-server fully-consistent)))
      (is (true? (eacl/can? *client super-user :reboot joe's-server fully-consistent)))

      (testing "...but joe's-user cannot :reboot my-server"
        (is (false? (eacl/can? *client joe's-user :reboot my-server fully-consistent)))))

    (testing "We can enumerate the platform administrators with read-relationships:"
      (is (= [(->Relationship super-user :super_admin ca-platform)]
             (eacl/read-relationships *client {:resource/type :platform}))))

    (testing "We can enumerate account owners:"
      (is (= [(->Relationship my-user :owner my-account)
              (->Relationship joe's-user :owner acme-account)]
             (eacl/read-relationships *client {:resource/type :account
                                               :subject/type  :user}))))

    (testing "read-relationships supports various filters:"
      (testing "query platform->account"
        (is (= [(->Relationship ca-platform :platform my-account)
                (->Relationship ca-platform :platform acme-account)]
               (eacl/read-relationships *client {:subject/type      :platform
                                                 :resource/type     :account
                                                 :resource/relation :platform}))))

      (testing "read-relationships throws if resource-type is not specified:"
        (is (= #{(->Relationship ca-platform :platform acme-account)
                 (->Relationship ca-platform :platform my-account)}
               (set (eacl/read-relationships *client {:subject/type :platform})))))

      (is (= [(->Relationship ca-platform :platform my-account)
              (->Relationship ca-platform :platform acme-account)]
             (eacl/read-relationships *client {:resource/type :account
                                               :subject/type  :platform})))

      (is (= [(->Relationship ca-platform :platform my-account)
              (->Relationship ca-platform :platform acme-account)]
             (eacl/read-relationships *client {:resource/type :account
                                               :subject/type  :platform}))))

    (testing "spice-read-relationships results are constrained by filters for resource type & ID"

      (testing "transact the test entities we are about to use"
        @(d/transact conn (for [object [(->account "test-account")
                                        (->account "other-account")
                                        (->vpc "my-vpc")
                                        (->vpc "other-vpc")]]
                            {:eacl/id   (:id object)})))

      (is (eacl/create-relationships! *client
                                      [(->Relationship (->account "test-account") :account (->vpc "my-vpc"))
                                       (->Relationship (->account "test-account") :account (->vpc "other-vpc"))
                                       (->Relationship (->account "other-account") :account (->vpc "other-vpc"))]))
      (is (= [(->Relationship (->account "test-account") :account (->vpc "my-vpc"))]
             (eacl/read-relationships *client {:resource/type     :vpc
                                               :resource/id       "my-vpc"
                                               :resource/relation :account
                                               :subject/type      :account
                                               :subject/id        "test-account"}))))

    ; expand-permission-tree not impl. yet.
    (testing "We can expand permissions hierarchy for (->server 123)."
      (is (= [[[[{:object   (->account "operativa")
                  :relation :owner
                  :subjects [{:object   (->user "ben")
                              :relation nil}]}
                 [{:object   (->platform "cloudafrica")
                   :relation :super_admin
                   :subjects [{:object   (->user "andre")
                               :relation nil}]}]]]

               ; no shared_admin subjects:
               []                                           ; don't know what this empty vector is about.
               {:object   (update (->server 123) :id str)
                :relation :shared_admin
                :subjects []}]

              ; no shared_member subjects:
              {:object   (update (->server 123) :id str)
               :relation :shared_member
               :subjects []}] (eacl/expand-permission-tree *client {:resource   (->server 123)
                                                                    :permission :reboot}))))

    (testing "Expand permissions hierarchy for joe's-server shows team member"
      ; Note: numeric IDs are not coerced back from strings yet.
      (is (= [[[[{:object   (->account "acme")
                  :subjects [{:object joe's-user :relation nil}],
                  :relation :owner}
                 [{:object   (->platform "cloudafrica"),
                   :subjects [{:object (->user "andre"), :relation nil}],
                   :relation :super_admin}]]]
               []
               {:object   (->server "not-my-server"),
                :subjects [],
                :relation :shared_admin}]
              {:object   (->server "not-my-server"),
               :subjects [],
               :relation :shared_member}] (eacl/expand-permission-tree *client {:resource   joe's-server
                                                                                :permission :reboot}))))))