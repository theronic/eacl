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

(defn page-start-cursor
  [page]
  (get-in page [:page-info :start-cursor]))

(defn page-end-cursor
  [page]
  (get-in page [:page-info :end-cursor]))

(deftest opaque-page-token-test
  (let [opts {:page-token-current-kid :test
              :page-token-keyring {:test (.getBytes "01234567890123456789012345678901" "UTF-8")}}]
    (testing "page-token round-trip preserves the encrypted pagination payload"
      (let [payload {:op :lookup-resources
                     :query-shape "shape"
                     :order [:eid :asc]
                     :basis :stable
                     :basis-t 42
                     :edge {:kind :lookup :result-eid 123}
                     :ttl-seconds 60}
            token (spiceomic/page-token opts payload)
            decoded (spiceomic/token->page-bound opts token)]
        (is (string? token))
        (is (.startsWith ^String token "eacl3_"))
        (is (= (dissoc payload :ttl-seconds)
               (select-keys decoded [:op :query-shape :order :basis :basis-t :edge])))))

    (testing "tokens use a fresh nonce for the same payload"
      (let [payload {:op :lookup-resources
                     :query-shape "shape"
                     :order [:eid :asc]
                     :basis :stable
                     :basis-t 42
                     :edge {:kind :lookup :result-eid 123}
                     :ttl-seconds 60}]
        (is (not= (spiceomic/page-token opts payload)
                  (spiceomic/page-token opts payload)))))

    (testing "keyring rotation accepts configured old keys and rejects missing keys"
      (let [old-opts {:page-token-current-kid :old
                      :page-token-keyring {:old (.getBytes "old-old-old-old-old-old-old-old-" "UTF-8")}}
            rotated-opts {:page-token-current-kid :new
                          :page-token-keyring {:old (.getBytes "old-old-old-old-old-old-old-old-" "UTF-8")
                                               :new (.getBytes "new-new-new-new-new-new-new-new-" "UTF-8")}}
            new-only-opts {:page-token-current-kid :new
                           :page-token-keyring {:new (.getBytes "new-new-new-new-new-new-new-new-" "UTF-8")}}
            payload {:op :lookup-resources
                     :query-shape "shape"
                     :order [:eid :asc]
                     :basis :stable
                     :basis-t 42
                     :edge {:kind :lookup :result-eid 123}
                     :ttl-seconds 60}
            old-token (spiceomic/page-token old-opts payload)]
        (is (= (:edge payload)
               (:edge (spiceomic/token->page-bound rotated-opts old-token))))
        (is (thrown? Throwable
                     (spiceomic/token->page-bound new-only-opts old-token)))))

    (testing "nil token decodes to nil"
      (is (nil? (spiceomic/token->page-bound opts nil))))

    (testing "invalid input is rejected"
      (is (thrown? Throwable (spiceomic/token->page-bound opts "garbage")))
      (is (thrown? Throwable (spiceomic/token->page-bound opts "eacl3_not-valid-base64!!!"))))))

(deftest spicedb-helper-tests
  (testing "spice-object takes [type id ?relation] and yields a SpiceObject with support for subject_relation"
    (is (= #eacl.core.SpiceObject{:type :user, :id "my-user", :relation nil}
           (spice-object :user "my-user")))
    (is (= #eacl.core.SpiceObject{:type :team, :id "dev-team", :relation :member}
           (spice-object :team "dev-team" :member)))))

(deftest spicedb-tests
  (with-mem-conn [conn schema/v6-schema]
    (testing "->user means (partial spice-object :user). Creates a SpiceObject record with {:keys [type id relation]}"
      (def my-user (->user "ben"))
      (def joe's-user (->user "joe"))
      (def super-user (->user "andre"))
      (def new-joiner (->user "new-joiner"))

      (testing "We treat subject & resource types as keywords in Clojure-land."
        (is (= :user (:type my-user)))
        (testing "in these fixtures IDs are strings, but you can tell EACL how resolve to/from internal ID in make-client opts"
          (is (= "ben" (:id my-user))))))

    (testing "To define a SubjectReference with a :relation (subject_relation), pass another arg to spice-object"
      (let [team-member (->team "my-team" :member)]
        (is (= :team (:type team-member)))
        (is (= "my-team" (:id team-member)))
        (is (= :member (:relation team-member)))))

    (testing "The platform definition is an abstraction to support super administrators"
      (def ca-platform (->platform "platform"))) ; todo: better name

    (testing "Define some server fixtures"
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

    @(d/transact conn (concat fixtures/relations+permissions fixtures/entity-fixtures))
    @(d/transact conn (fixtures/relationship-fixtures (d/db conn))) ; temp until write-relationships

    ;(is (= [] (eacl/read-relationships *client {:resource/type :vpc})))
    ;(is (= [] (eacl/read-relationships *client {:resource/type :account})))
    ;(is (= [] (eacl/read-relationships *client {:resource/type :server})))

    ; TODO: bring back relationship deletion tests.
    ;
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

    #_(testing "Clean up prior relationships if non-nil schema (for REPL testing)"
        ; conditional due to Spice segfault when using in-memory datastore under certain conditions.
        (let [?schema-string (try (eacl/read-schema *client) (catch Exception ex nil))]
          (log/debug "schema:" ?schema-string)
          (when true                                        ;?schema-string
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
                       (eacl/delete-relationships! *client))))))
        (try
          (->> (eacl/read-relationships *client {:resource/type :server
                                                 :consistency   fully-consistent})
               (eacl/delete-relationships! *client))
          (catch Exception _)))

    #_(testing "Migrate schema via IG – will throw if new schema would orphan any relationships"
        (let [{:spicedb/keys [migrate]} (ig/init (igc/integrant-definition) [:spicedb/migrate])]
          ;(testing ":spicedb/migrate returns a WriteSchemaResponse, which is result of eacl/write-schema!"
          ;  (is (= com.authzed.api.v1.SchemaServiceOuterClass$WriteSchemaResponse (class migrate))))
          (testing "Ensure we can read a valid Spice schema after :spicedb/migrate"
            (let [spice-schema-string (try (eacl/read-schema *client) (catch Exception ex ""))]
              (is (false? (string/blank? spice-schema-string)))
              (testing "Spice schema string contains at least one Spice `definition`"
                (is (re-find #"definition" spice-schema-string)))))))

    ;(testing "After deletion, read-relationships should an return empty seq for :server resources."
    ;  (is (= [] (eacl/read-relationships *client {:resource/type :server}))))

    (testing "Given an empty permission system, ensure no one can reboot my-server"
      ; We specify consistency/fully-consistent because permissions are cached and can? defaults to minimize-latency.
      (is (false? (eacl/can? *client my-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client joe's-user :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client new-joiner :reboot my-server fully-consistent)))
      (is (false? (eacl/can? *client super-user :reboot my-server fully-consistent))))

    (testing "transact the entities with :eacl/id we are about to use so [:eacl/id 'ben'] resolves"
      ; TODO: this is outdated. rather test against the newer base-fixtures.
      (is @(d/transact conn (for [ent [ca-platform
                                       my-account acme-account
                                       super-user my-user joe's-user
                                       my-server my-other-server joe's-server]]
                              {:eacl/id (:id ent)}))))

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
	      ; fixtures a bit confusing atm.
      (is (= #{(->Relationship super-user :super_admin ca-platform)
               (->Relationship (->user "super-user") :super_admin ca-platform)}
             (set (:data (eacl/read-relationships *client {:resource/type :platform}))))))

    (testing "We can enumerate account owners:"
      (is (= #{(->Relationship my-user :owner my-account)
               (->Relationship joe's-user :owner acme-account)
               (->Relationship (->user "user-1") :owner (->account "account-1"))
               (->Relationship (->user "user-2") :owner (->account "account-2"))}
             (set (:data (eacl/read-relationships *client {:resource/type :account
                                                           :subject/type  :user}))))))

    (testing "read-relationships supports various filters:"
      (testing "query platform->account"
        (is (= #{(->Relationship ca-platform :platform acme-account)
                 (->Relationship ca-platform :platform (->account "account-1"))
                 (->Relationship ca-platform :platform (->account "account-2"))
                 (->Relationship ca-platform :platform my-account)}
               (set (:data (eacl/read-relationships *client {:resource/type     :account
                                                             :subject/type      :platform
                                                             :resource/relation :platform}))))))

      (testing "the same relationships show up if we omit :resource/relation"
        (is (= #{(->Relationship ca-platform :platform acme-account)
                 (->Relationship ca-platform :platform (->account "account-1"))
                 (->Relationship ca-platform :platform (->account "account-2"))
                 (->Relationship ca-platform :platform my-account)}
               (set (:data (eacl/read-relationships *client {:resource/type :account
                                                             :subject/type  :platform})))))))

    (testing "read-relationships supports forward and backward page cursors"
      (let [base-query {:resource/type :account
                        :subject/type :platform
                        :resource/relation :platform}
            all-page (eacl/read-relationships *client (assoc base-query :first 10))
            [page1-expected page2-expected] (partition-all 2 (:data all-page))
            page1 (eacl/read-relationships *client (assoc base-query :first 2))
            page2 (eacl/read-relationships *client
                                           (assoc base-query
                                                  :first 2
                                                  :after (page-end-cursor page1)))
            previous-page (eacl/read-relationships *client
                                                   (assoc base-query
                                                          :last 2
                                                          :before (page-start-cursor page2)))]
        (is (= page1-expected (:data page1)))
        (is (= page2-expected (:data page2)))
        (is (= (:data page1) (:data previous-page)))
        (is (string? (page-start-cursor page1)))
        (is (string? (page-end-cursor page1)))
        (is (.startsWith ^String (page-end-cursor page1) "eacl3_"))
        (is (true? (get-in page1 [:page-info :has-next-page?])))
        (is (false? (get-in page1 [:page-info :has-previous-page?])))
        (is (true? (get-in previous-page [:page-info :has-next-page?])))))

    (testing "lookup-resources pagination tests"
      (let [base-query {:first 2
                        :resource/type :server
                        :permission :view
                        :subject (->user "super-user")}
            page1 (eacl/lookup-resources *client base-query)
            page1-data (:data page1)
            page1-end-cursor (page-end-cursor page1)
            page2 (eacl/lookup-resources *client (assoc base-query :after page1-end-cursor))
            page2-data (:data page2)
            previous-page (eacl/lookup-resources *client
                                                 (-> base-query
                                                     (dissoc :first :after)
                                                     (assoc :last 2
                                                            :before (page-start-cursor page2))))]
        (is (= [(spice-object :server "account1-server1")
                (spice-object :server "account1-server2")]
               page1-data))

        (is (= [(spice-object :server "account2-server1")
                my-server]
               page2-data))

        (testing "page-info contains opaque eacl3 tokens and no legacy top-level cursor"
          (is (nil? (:cursor page1)))
          (is (string? (page-start-cursor page1)))
          (is (string? page1-end-cursor))
          (is (.startsWith ^String page1-end-cursor "eacl3_")))

        (testing "reverse pagination can get the previous page without a cursor stack"
          (is (= page1-data (:data previous-page)))
          (is (true? (get-in previous-page [:page-info :has-next-page?])))
          (is (false? (get-in previous-page [:page-info :has-previous-page?]))))

        (testing "page tokens are bound to the original query shape and protected against tampering"
          (is (thrown? Throwable
                       (eacl/lookup-resources *client
                                              {:first 2
                                               :after page1-end-cursor
                                               :resource/type :account
                                               :permission :view
                                               :subject (->user "super-user")})))
	          (is (thrown? Throwable
	                       (eacl/lookup-resources *client
	                                              (assoc base-query :after (str page1-end-cursor "x"))))))))

	    (testing "count-resources returns a full count and rejects list pagination keys"
	      (let [{:keys [count limit]} (eacl/count-resources *client
	                                                        {:subject       (->user "super-user")
	                                                         :permission    :view
	                                                         :resource/type :server})]
	        (is (pos? count))
	        (is (= -1 limit)))
	      (is (thrown? Throwable
	                   (eacl/count-resources *client
	                                         {:subject       (->user "super-user")
	                                          :permission    :view
	                                          :resource/type :server
	                                          :first         2}))))

	    (testing "stable page tokens keep using the page basis after live changes"
	      (let [base-query {:resource/type :server
	                        :permission :view
	                        :subject (->user "super-user")}
	            page1 (eacl/lookup-resources *client (assoc base-query :first 2))
	            page1-end-cursor (page-end-cursor page1)
	            stable-rest-before-insert (:data (eacl/lookup-resources *client
	                                                                    (assoc base-query
	                                                                           :first 100
	                                                                           :after page1-end-cursor)))
	            new-server (->server "stable-new-server")]
	        @(d/transact conn [{:eacl/id (:id new-server)}])
	        (is (eacl/create-relationship! *client my-account :account new-server))
	        (is (= stable-rest-before-insert
	               (:data (eacl/lookup-resources *client
	                                            (assoc base-query
	                                                   :first 100
	                                                   :after page1-end-cursor)))))
	        (is (not-any? #{new-server}
	                      (:data (eacl/lookup-resources *client
	                                                   (assoc base-query
	                                                          :first 100
	                                                          :after page1-end-cursor))))))
	      (let [base-query {:resource/type :server
	                        :permission :view
	                        :subject (->user "super-user")}
	            page1 (eacl/lookup-resources *client (assoc base-query :first 2))
	            page1-end-cursor (page-end-cursor page1)
	            expected-page2 (eacl/lookup-resources *client
	                                                  (assoc base-query
	                                                         :first 2
	                                                         :after page1-end-cursor))
	            victim (first (:data expected-page2))]
	        @(d/transact conn [[:db/retract [:eacl/id (:id victim)] :eacl/id (:id victim)]])
	        (is (= (:data expected-page2)
	               (:data (eacl/lookup-resources *client
	                                            (assoc base-query
	                                                   :first 2
	                                                   :after page1-end-cursor)))))))

	    (testing "spice-read-relationships results are constrained by filters for resource type & ID"
	      (testing "transact the test entities we are about to use"
	        @(d/transact conn (for [object [(->account "test-account")
	                                        (->account "other-account")
	                                        (->vpc "my-vpc")
	                                        (->vpc "other-vpc")]]
	                            {:eacl/id (:id object)})))

	      (is (eacl/create-relationships! *client
	                                      [(->Relationship (->account "test-account") :account (->vpc "my-vpc"))
	                                       (->Relationship (->account "test-account") :account (->vpc "other-vpc"))
	                                       (->Relationship (->account "other-account") :account (->vpc "other-vpc"))]))
	      (is (= [(->Relationship (->account "test-account") :account (->vpc "my-vpc"))]
	             (:data (eacl/read-relationships *client {:resource/type     :vpc
	                                                      :resource/id       "my-vpc"
	                                                      :resource/relation :account
	                                                      :subject/type      :account
	                                                      :subject/id        "test-account"})))))))

; expand-permission-tree not impl. yet.
;; FIXME: These tests fail because expand-permission-tree is not implemented yet
#_(testing "We can expand permissions hierarchy for (->server 123)."
    (is (= [[[[{:object   (->account "operativa")
                :relation :owner
                :subjects [{:object   (->user "ben")
                            :relation nil}]}
               [{:object   (->platform "sample-platform")
                 :relation :super_admin
                 :subjects [{:object   (->user "andre")
                             :relation nil}]}]]]

             ; no shared_admin subjects:
             []                                             ; don't know what this empty vector is about.
             {:object   (update (->server 123) :id str)
              :relation :shared_admin
              :subjects []}]

            ; no shared_member subjects:
            {:object   (update (->server 123) :id str)
             :relation :shared_member
             :subjects []}] (eacl/expand-permission-tree *client {:resource   (->server 123)
                                                                  :permission :reboot}))))

#_(testing "Expand permissions hierarchy for joe's-server shows team member"
    ; Note: numeric IDs are not coerced back from strings yet.
    (is (= [[[[{:object   (->account "acme")
                :subjects [{:object joe's-user :relation nil}],
                :relation :owner}
               [{:object   (->platform "sample-platform"),
                 :subjects [{:object (->user "andre"), :relation nil}],
                 :relation :super_admin}]]]
             []
             {:object   (->server "not-my-server"),
              :subjects [],
              :relation :shared_admin}]
            {:object   (->server "not-my-server"),
             :subjects [],
             :relation :shared_member}] (eacl/expand-permission-tree *client {:resource   joe's-server
                                                                              :permission :reboot}))))

;; todo: test that shows behaviour of read-relationships when subject or resource is missing.
