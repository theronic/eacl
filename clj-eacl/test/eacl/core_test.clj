(ns eacl.core-test
  (:require
    ;[bridge.utils :refer [spy profile]]
    [clojure.test :as t :refer (deftest is testing)]
    [taoensso.timbre :as log]
    [eacl.core :as eacl]
    [eacl.data.schema :as schema]
    [datahike.api :as d]
    [datahike.core :as dc]
    [clojure.spec.test.alpha :as st]))
;[bridge.fixtures.common-fixtures :as f :refer (*conn *db)]
;[bridge.fixtures.catalog-fixtures :as cf]
;[bridge.fixtures.auth-fixtures :as af]
;[bridge.fixtures.auth-fixtures :as fa]))

(def n-iterations 150)                                      ;; was 100000. belongs under perf/benchmark tests.

(def ^:dynamic *conn nil)

(defn create-mem-conn
  [& [tx-initial]]
  (let [cfg-mem {:store {:backend :mem :id (str 123)}}]     ;(str (rand-int 1000))}}] ;(nano/nano-id)}}]
    (d/create-database cfg-mem :tx-initial tx-initial)
    (let [conn (d/connect cfg-mem)]
      (when tx-initial
        (d/transact conn tx-initial))
      conn)))

(defn conn-fixture [test-fn]
  (st/instrument)
  (let [conn (create-mem-conn schema/v1-eacl-schema)]
    ;(mount/start-with {#'bridge.data.datahike/conn conn})
    (with-bindings {#'*conn conn}
      (test-fn))
    ;; should we call destroy here?
    (d/release conn)
    ;(mount/stop) ;; hmm
    (st/unstrument)
    true))

(defn eacl-fixtures [test-fn]
  (assert *conn)
  (d/transact *conn schema/v1-eacl-schema)
  (d/transact *conn [{:db/ident       :contact/full-name
                      :db/valueType   :db.type/string
                      :db/cardinality :db.cardinality/one}

                     {:db/ident       :user/contact
                      :db/valueType   :db.type/ref
                      :db/cardinality :db.cardinality/one}

                     {:db/ident       :invoice/number
                      :db/valueType   :db.type/string
                      :db/unique      :db.unique/identity
                      :db/cardinality :db.cardinality/one}])

  ;(let [conn (->> {:db/ident       :invoice/number
  ;                 :db/valueType   :db.type/string
  ;                 :db/unique      :db.unique/identity
  ;                 :db/cardinality :db.cardinality/one}
  ;             (conj schema/v1-eacl-schema)
  ;             (d/create-mem-conn))])

  (d/transact *conn
    [{:db/ident   :invoices
      :eacl/ident :invoices}])

  ;(eacl/grant! *conn #:eacl{:who [:eacl/ident :jan-hendrik]
  ;                          :what :invoice/number})

  (time
    (d/transact *conn
      (vec (for [inv-num (range 1 (inc n-iterations))]
             {:db/id          (- inv-num)
              :invoice/number (str "INV" inv-num)
              :eacl/parent    [:eacl/ident :invoices]}))))

  (d/transact *conn
    [{:db/id      [:eacl/ident :invoices]
      :eacl/path  "invoices"
      :eacl/ident :invoices}
     {:db/id    -2
      :db/ident :petrus}])

  ;(with-bindings {#'*conn conn})
  (test-fn))

;(d/release *conn))

;(deftest fixture-sanity
;  (testing "can transact fixtures against empty-db"
;    (eacl-fixtures (fn []))))
;(d/transact (d/empty-db) eacl-fixtures)))

(comment
  (mount.core/start))

;; hmm yeah it would be good to not rely on any bridge schema.

(t/use-fixtures :each conn-fixture eacl-fixtures)           ;fa/auth-fixtures) ; eacl-fixtures)

;(t/use-fixtures :each f/conn-fixture af/auth-fixtures cf/catalog-fixtures eacl-fixtures)

;(clojure.test/use-fixtures my-fixtures)

;(doseq [inv-num (range n-iterations)]
;  (d/transact conn {:invoice/number inv-num
;                    :eacl/parent    [:eacl/ident :invoices]}))

;(deftest grant-deny)
;(let [conn (d/create-mem-conn (conj schema/v1-eacl-schema {:db/ident       :invoice/number
;                                                           :db/valueType   :db.type/string
;                                                           :db/unique      :db.unique/identity
;                                                           :db/cardinality :db.cardinality/one}))]))
;(eacl/grant! conn #:eacl{:who :petrus
;                         :what :invoices
;                         :how :read})))

(def crud #{:create :read :update :delete})

;'{:find [(pull ?rule [*])],
;  :in [$ % ?allow ?who ?what ?why ?when ?where ?how],
;  :where [[?rule :eacl/allowed? ?allow] [?rule :eacl/who ?who] [?rule :eacl/what ?what] [?rule :eacl/where ?where] [?rule :eacl/how ?how]]
;  ([[(child-of ?c ?p) (?c :eacl/parent ?p)] [(child-of ?c ?p) (?c1 :eacl/parent ?p) (child-of ?c ?c1)]]
;   false :jan-hendrik
;   :product/name
;   #{}
;   nil
;   :test-store
;   :create)}

;'[:find ?rule
;  :where
;  [?rule :eacl/allowed? true]
;  [?rule :eacl/where :test-store]
;  [?rule :eacl/what :product/name]
;  [?rule :eacl/who :jan-hendrik]]

(comment

  (eacl-fixtures
    (fn []
      ;(d/transact *conn [{:db/ident   :jan-hendrik
      ;                    :eacl/ident :jan-hendrik}
      ;                   {:eacl/ident :test-store}
      ;                   {:db/ident :product/name}])

      (let [rule {:eacl/who   [:eacl/ident :jan-hendrik]
                  :eacl/what  [:db/ident :product/name]
                  :eacl/where [:eacl/ident :test-store]
                  :eacl/how   :create}]
        (eacl/grant! *conn rule)
        (let [db (d/db *conn)]
          (log/warn
            "TEST"
            (d/q
              '[:find ?rule
                :in $ ?who
                :where
                [?rule :eacl/allowed? true]
                [?rule :eacl/where :test-store]
                [?rule :eacl/what :product/name]
                [?rule :eacl/who ?who]]
              (d/db *conn)
              [:db/ident :jan-hendrik]))

          ;(let [{:eacl/keys [who what why when where how]} demand]
          ;  (log/warn "CUSTOM" (into [] (d/q
          ;                                (eacl/build-rule-query demand)
          ;                                (d/db *conn) eacl/child-rules
          ;                                true [:db/ident :jan-hendrik]))))
          ;(or who #{})))))
          ;(or what #{})
          ;(or why #{})
          ;(or when #{})
          ;(or where #{})
          ;(or how #{})))))

          (log/warn (eacl/can? db rule)))))))

(deftest sanity-tests
  (testing "can? doesn't blow up"
    (let [db (d/db-with (dc/empty-db) schema/v1-eacl-schema)]
      (is (false? (eacl/can? db #:eacl{:who   [:eacl/ident :me]
                                       :what  [:eacl/ident :thing]
                                       :where [:eacl/ident :anywhere]
                                       :how   :read}))))))  ;[:eacl/ident :read]})))))


(comment
  (eacl/can? @*conn {:eacl/who   [:eacl/ident :alex]
                     :eacl/how   :read
                     :eacl/read  [:docket/number 567]
                     :eacl/where #inst "2000-05-05"}))

;(defn create-order! [db order-number lines]
;  (eacl/can! db {:eacl/who :alex
;                 :eacl/what :order/number
;                 :eacl/how :create
;                 :eacl/when (t/now)
;                 :eacl/why ""}))

(deftest recursion-tests
  (testing "that child inherits parent's rules"
    (is (d/transact *conn [{:db/id -1 :eacl/ident :alice}
                           {:db/id -2 :eacl/ident :bob}]))
    (d/transact *conn [[:db/add [:db/ident :invoice/number] :eacl/parent [:db/ident :invoice/number]]]) ;; required for most queries to work. Big todo.
    (is (eacl/create-role! *conn {:db/ident :manager}))
    (is (false? (eacl/can? @*conn #:eacl {:who  [:db/ident :manager]
                                          :what [:db/ident :invoice/number]
                                          :how  :read})))
    (is (eacl/grant! *conn #:eacl {:who  [:db/ident :manager]
                                   :what [:db/ident :invoice/number]
                                   :how  :read}))
    (is (true? (eacl/can? @*conn #:eacl {:who  [:db/ident :manager]
                                         :what [:db/ident :invoice/number]
                                         :how  :read})))))
;(is (true? (eacl/can? @*conn #:eacl {:who  [:db/ident :manager]
;                                     :what [:db/ident :invoice/number]
;                                     :how  :read})))
;(d/transact *conn [[:db/ident :alice] :eacl/parent [:db/ident :manager]])
;(eacl/can? @*conn #:eacl {:who [:db/ident :alice]})))
;(eacl/assign-role-at!)
;(eacl/grant! *conn #:eacl {:who [:db/ident]})))

(deftest store-rules
  (is (d/transact *conn [{:db/ident       :product/name
                          :db/valueType   :db.type/string
                          :db/cardinality :db.cardinality/one}

                         {:db/ident       :store/name
                          :db/valueType   :db.type/string
                          :db/cardinality :db.cardinality/one}

                         {:db/ident     :invoice/number
                          :db/valueType :db.type/string
                          :db/unique    :db.unique/identity}

                         {:db/ident       :invoice/total
                          :db/valueType   :db.type/bigdec
                          :db/cardinality :db.cardinality/one
                          :db/index       true}

                         {:db/ident       :contact/name
                          :db/valueType   :db.type/string
                          :db/cardinality :db.cardinality/one}]))

  ;(d/transact *conn [{:db/id "role"
  ;                    :eacl/ident :role/store-manager
  ;                    :eacl/role "role"}])

  (is (d/transact *conn
        [{:db/id       "store"
          :eacl/ident  :test-store
          :eacl/parent "store"                              ;; required
          :eacl/role   "store"                              ;; required
          :store/name  "Ancestral Nourishment Store"}

         {:db/id             "contact"
          :contact/full-name "Jan-Hendrik"}

         {:db/id       "invoices"
          :eacl/parent "invoices"                           ;; NB MUST BE OWN PARENT.
          :eacl/ident  :invoices}

         {:db/id        "user"
          :eacl/ident   :jan-hendrik
          :eacl/parent  "user"                              ;; hack
          :eacl/role    "user"                              ;; hack
          :user/contact "contact"}]))

  (prn "write invoices:")
  (time
    (d/transact *conn
      (vec (for [inv-num (range 1 (inc n-iterations))]
             {:db/id          (- inv-num)
              :invoice/number (str "INV" inv-num)
              :eacl/parent    [:eacl/ident :invoices]}))))

  (prn "invoices:" (d/q '[:find ?num :where [?inv :invoice/number ?num]] @*conn))

  ;(is (d/transact *conn [{:invoice/number "INV123"
  ;                        :eacl/parent    [:db/ident :invoice/number]
  ;                        :invoice/total  123.45M}]))

  (is (eacl/create-role! *conn {:eacl/ident :role/store-manager}))
  ;(is (eacl/assign-role-at! *conn
  ;      [:eacl/ident :jan-hendrik]
  ;      [:eacl/ident :role/store-manager] ;; need some sugar here.
  ;      [:eacl/ident :test-store]))

  (is (d/transact *conn [{:invoice/number "INV123"          ;; will complain
                          :invoice/total  123.45M
                          :eacl/parent    [:eacl/ident :invoices]}]))
  ;:eacl/parent [:eacl/ident :test-store]}]))

  (is (false? (eacl/can? @*conn #:eacl {:who  [:eacl/ident :jan-hendrik]
                                        :what [:invoice/number "INV123"]})))

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                        :what  [:invoice/number "INV123"]
                                        :where [:eacl/ident :test-store]})))

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :jan-hendrik]
                                        :what  [:db/ident :invoice/number] ;[:invoice/number "INV123"]
                                        :where [:eacl/ident :test-store]})))

  ;(is (eacl/grant! *conn #:eacl {:who   [:eacl/ident :jan-hendrik]
  ;                               :what  :invoice/number ;[:invoice/number "INV123"]
  ;                               :where [:eacl/ident :test-store]}))

  (is (eacl/grant! *conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                 :what  [:eacl/ident :invoices] ;:invoice/number]   ;[:invoice/number "INV123"]
                                 :where [:eacl/ident :test-store]}))

  (is (true? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                       :what  [:eacl/ident :invoices] ;:invoice/number]   ;[:invoice/number "INV123"]
                                       :where [:eacl/ident :test-store]})))

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :jan-hendrik]
                                        :what  [:eacl/ident :invoices] ;:invoice/number]   ;[:invoice/number "INV123"]
                                        :where [:eacl/ident :test-store]})))

  ;(is (eacl/grant! *conn #:eacl {:who   [:eacl/ident :role/store-manager]
  ;                               :what  :invoices   ;[:invoice/number "INV123"]
  ;                               :where [:eacl/ident :test-store]}))

  (testing "give test user fine-grained access to a particular invoice"
    (is (eacl/grant! *conn #:eacl {:who   [:eacl/ident :jan-hendrik]
                                   :what  [:invoice/number "INV123"] ;[:eacl/ident :invoices] ;/number]   ;[:invoice/number "INV123"]
                                   :where [:eacl/ident :test-store]})))

  (testing "we can retrieve the grant just issued"
    (let [[allowed? rule] (first (d/q '[:find ?allowed (pull ?rule [* {:eacl/who   [:eacl/ident]
                                                                       :eacl/what  [:eacl/ident :invoice/number]
                                                                       :eacl/where [:eacl/ident]}])
                                        :in $ ?who
                                        :where
                                        [?rule :eacl/who ?who]
                                        [?rule :eacl/allowed? ?allowed]]
                                   @*conn [:eacl/ident :jan-hendrik]))]
      (is (= true allowed?))))

  ;(is (d/transact *conn [[:db/add [:invoice/number "INV123"] :eacl/parent :invoice/number]]))

  (prn "xxx HERE")

  ;(d/q '[:find ?rule
  ;       :where
  ;       [?rule :eacl/who [:eacl/ident :jan-hendrik]]])

  (testing "previous grant! has taken effect"               ;; why is this not working?
    (is (true? (eacl/can? @*conn #:eacl{:who   [:eacl/ident :jan-hendrik]
                                        :what  [:invoice/number "INV123"] ;; todo inherit place.
                                        :where [:eacl/ident :test-store]}))))

  (is (eacl/deny! *conn #:eacl {:who   [:eacl/ident :jan-hendrik]
                                :what  [:invoice/number "INV123"] ;; todo inherit place.
                                :where [:eacl/ident :test-store]}))

  ;; why isn't deny working?

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :jan-hendrik]
                                        :what  [:invoice/number "INV123"] ;; todo inherit place.
                                        :where [:eacl/ident :test-store]})))

  (prn "Manager: " (d/pull @*conn '[*] [:eacl/ident :role/store-manager]))
  (prn (d/pull @*conn '[*] [:eacl/ident :jan-hendrik]))

  (d/transact *conn [[:db/add [:db/ident :invoice/number] :eacl/parent [:eacl/ident :invoices]]])

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                        :what  [:db/ident :eacl/who] ;[:invoice/number "INV123"]
                                        :where [:eacl/ident :test-store]})))

  (is (true? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                       :what  [:db/ident :invoice/number] ;[:invoice/number "INV123"]
                                       :where [:eacl/ident :test-store]})))

  (is (eacl/grant! *conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                 :what  :invoice/number     ;[:invoice/number "INV123"]
                                 :where [:eacl/ident :test-store]}))

  (is (true? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                       :what  [:db/ident :invoice/number] ;[:invoice/number "INV123"]
                                       :where [:eacl/ident :test-store]})))

  (is (eacl/deny! *conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                :what  :invoice/number      ;[:invoice/number "INV123"]
                                :where [:eacl/ident :test-store]}))

  (is (false? (eacl/can? @*conn #:eacl {:who   [:eacl/ident :role/store-manager]
                                        :what  [:db/ident :invoice/number] ;[:invoice/number "INV123"]
                                        :where [:eacl/ident :test-store]})))

  ;(eacl/grant! *conn #:eacl {:who  [:eacl/ident :jan-hendrik]
  ;                           :where [:eacl/ident :test-store]
  ;                           :what [:invoice/number "INV123"]})

  (d/transact *conn [[:db/add [:eacl/ident :jan-hendrik] :eacl/parent [:eacl/ident :role/store-manager]]])

  (is (true? (eacl/can? @*conn
               #:eacl {:who   [:eacl/ident :jan-hendrik]
                       :what  :invoice/number               ;[:invoice/number "INV123"]
                       :where [:eacl/ident :test-store]})))
  ;(eacl)

  (is (d/transact *conn [{:db/ident       :event/key
                          :db/doc         "Event keyword to trigger subscription notifications."
                          :db/valueType   :db.type/keyword
                          :db/unique      :db.unique/identity
                          :db/cardinality :db.cardinality/one}]))

  ;; subscribe vs grant?

  ;; Todo: use EACL to validate itself for e.g. granting new rules.

  (is (eacl/grant! *conn #:eacl{;:when  {:event/key :event/new-order} ;; inheritance
                                :who   [:eacl/ident :role/store-manager] ;; vs. view vs. see?
                                ;; todo: support '1 year' from now for times.
                                :how   :read                ;; :notify? all aspects? what about children of the store?
                                ;; Todo: think about how to support arbitrary queries in :what. Maybe call it query for subs.
                                ;:what  '[*] ;; hmm not valid. how to implement this?
                                :where [:eacl/ident :test-store] ;; better name
                                :why   :email/notification}))

  ;; how to mix

  (is (eacl/enum-event-triggers @*conn :event/new-order))

  ;; is EACL the right place for this kind of topic subscription?

  (let [test-rule #:eacl{:who   [:eacl/ident :jan-hendrik]  ;[:db/ident :jan-hendrik]
                         :what  [:db/ident :product/name]
                         :where [:eacl/ident :test-store]   ;[:db/ident :test-store]
                         :how   :create}]
    (eacl/grant! *conn test-rule)

    ;; how can we enum all the rules for this user?

    (let [db         @*conn
          user-rules (eacl/enum-user-rules db [:eacl/ident :jan-hendrik])
          jan        (d/entity db [:eacl/ident :jan-hendrik])]
      (log/debug "User rules:" user-rules)
      ;; need some jigs to test entity equality.
      ;; do pulls return entities?
      (let [rule1 (ffirst user-rules)]
        (is (= (:db/id (first (:eacl/who rule1)) (:db/id jan))))))
    ;        (is (= (:eacl/who rule1) {:eacl/who [:eacl/ident :jan-hendrik]}))))

    (is (true? (eacl/can? (d/db *conn) test-rule)))))

(deftest notify-tests
  (testing "who can?"
    (let [db @*conn]
      (log/debug "Who:" (eacl/who-can? db #:eacl{:who   '?who
                                                 :what  [:db/ident :product/name]
                                                 :where [:eacl/ident :test-store]
                                                 :how   :create})))))

;(deftest can?-multi-arity                                   ;; how should this work?
;  (is (true? (eacl/can? (d/db *conn)
;               #:eacl{:who   #{[:db/ident :jan-hendrik]}          ;[:db/ident :jan-hendrik]
;                      :what  [:db/ident :product/name]
;                      :where [:db/ident :test-store]        ;[:db/ident :test-store]
;                      :how   :create}))))

;; what's the simplest thing that can work for notifications?
;; assign store roles. Well basically that's what a rule is.
;; We just need to match the what and the where.

;(deftest notify-tests
;  (testing "who can?"
;    (let [db @*conn]
;      (is (eacl/who-can? db {}))))
;
;  (testing "can we use EACL roles for notifications?"
;    ;; generalised subscriptions? coz well, if someone edits something, we know right.
;    ;; Need differential dataflow here.
;
;    ;; what should notification API look like? Basically we are subscribing to entities with an :sales-order/number + user has a certain right at :sales-order/store.
;    ;; could we design rule queries that they are unified?
;    ;; in this case it's basically `?order :sales-order/store ?store` for any ?order but limited ?store.
;    ;; Can we use Datomic listeners for this + filter?
;
;    ;; why vs how for order notifications?
;
;    (let [notify-rule #:eacl{:who   [:db/ident :jan-hendrik]
;                             :where [:db/ident :test-store]
;                             :why   :notify/new-order
;                             :how   '[*]                    ;; hmm any attribute. Would be cool if this qorks as expected.
;                             ;; can what be new incoming orders?
;                             ;; can :what be a recursive pull for the order we care about?
;                             :what  :sales-order/number}]
;      (eacl/grant! *conn notify-rule)
;      (let [db @*conn]
;        (is (not (eacl/can? db (dissoc notify-rule :eacl/who))))
;        (is (not (eacl/can? db (assoc notify-rule :eacl/who [:db/ident :petrus]))))
;        (is (eacl/can? db notify-rule))))
;
;    ;'[:find (pull ?order [*])
;    ;  :where
;    ;  [?order :sales-order/number ?number
;    ;   ?order :sales-order/store ?store
;    ;   ;; we have to name these order notification.
;    ;   ?rule :eacl/what ?order ;; :sales-order/number ;; hmm.
;    ;   ?rule :eacl/where ?store
;    ;   ?rule :eacl/allowed? true
;    ;   ?rule :eacl/who ?who]]
;    (let [group (eacl/create-group! *conn {:group/name "Test"})
;          db    @*conn
;          rule  #:eacl{:how  :notify/order-placed           ;[:db/ident :test-store]
;                       ;; note the absence of ?who.
;                       ;; hmm, can we use datomic listener + filter here? No, has to be indexed queryable.
;                       :what [:db/ident :test-store]}])
;      ;(eacl/enum-who-can db rule))
;    ;(eacl/who-can? db))
;    ;(eacl/can? @*conn #:eacl{:who   ?who
;    ;                         :what  [:db/ident :test-store]
;    ;                         :where []
;    ;                         ;; hmm, could :why be the answer here?
;    ;                         :how   :notify/order-placed}))
;    ()))

;(deftest enum-tests
;  (d/transact *conn
;    [{:db/id      -1
;      :eacl/path  "invoices"
;      :eacl/ident :invoices}
;     {:db/id    -2
;      :db/ident :petrus}])
;
;  (time
;    (d/transact *conn
;      (vec (for [inv-num (range 1 (inc n-iterations))]
;             {:db/id          (- inv-num)
;              :invoice/number (str "INV" inv-num)
;              :eacl/parent    [:eacl/ident :invoices]}))))
;
;  (is (empty? (log/debug "Enum 1:" (eacl/enum-user-rules (d/db *conn) [:db/ident :petrus]))))
;
;  ;(comment
;  ;  (let [conn (d/create-mem-conn (conj schema/v1-eacl-schema {:db/ident       :invoice/number
;  ;                                                             :db/valueType   :db.type/string
;  ;                                                             :db/unique      :db.unique/identity
;  ;                                                             :db/cardinality :db.cardinality/one}))
;  ;        rule {:eacl/who  [:db/ident :petrus]
;  ;              :eacl/what [:eacl/ident :invoices]
;  ;              ;:eacl/where [:eacl/ident :invoices] ;;temp
;  ;              :eacl/how  :read}]
;  ;    (d/transact conn (eacl/tx-rule true rule))))
;
;  (let [rule {:eacl/who  [:db/ident :petrus]
;              :eacl/what [:eacl/ident :invoices]
;              :eacl/how  :read}]
;    (d/transact *conn [{:db/id       -1
;                        :eacl/parent -1                     ;; own parent
;                        :db/ident    :petrus}
;                       {:db/id       -2
;                        :eacl/ident  :invoices
;                        :eacl/parent -2}])
;
;    ;(eacl/tx-rule true rule)
;
;    (is (false? (eacl/can? (d/db *conn) rule)))
;    (eacl/grant! *conn rule)
;    (log/debug "Enum 2:" (eacl/enum-user-rules (d/db *conn) [:db/ident :petrus]))
;    (is (= 1 (count (eacl/enum-user-rules (d/db *conn) [:db/ident :petrus]))))
;    ;;(log/debug "Enum 2:" (eacl/enum-user-permissions @conn (:db/id who)))
;    (log/debug "qry:" (eacl/build-rule-query rule))
;    (is (true? (eacl/can? (d/db *conn) rule)))
;
;    (eacl/deny! *conn rule)
;    (is (false? (time (eacl/can? (d/db *conn) rule))))
;
;    ;; Thinking about rule inheritance:
;    ;; 1. Specific overrides general rules.
;    ;; 2. Given a tie, deny overrules allow.
;    ;; 3. How can we use rules prospectively to see if a transaction will trigger any rules?
;
;    (eacl/grant! *conn rule)                                ;; should this complain about a conflicting rule, or should it override the deny-rule?
;    (is (true? (eacl/can? (d/db *conn) rule)))))

;(is (false? (eacl/can? (d/db conn) {:db/}))))))

;(is (false? (eacl/can? (d/db conn)))))))                   ; (:db/id invoices)}))))

;;
;
;  (let [db @conn]
;    (is (false? (eacl/can? db {:eacl/who  who             ;(:db/id user)
;                               :eacl/what what
;                               :eacl/how  :read})))       ; (:db/id invoices)}))))
;    (eacl/grant! conn {:eacl/who  who
;                       :eacl/what what
;                       :eacl/how  :read})
;
;    (log/debug "Enum 2:" (eacl/enum-user-permissions @conn (:db/id who)))
;    (is (true? (eacl/can? @conn {:eacl/user     who       ; (:db/id user)
;                                 :eacl/action   :read
;                                 :eacl/resource what})))  ; (:db/id invoices)}))))))
;    (eacl/deny! conn {:eacl/who  who
;                      :eacl/what what
;                      :eacl/how  :read})
;    (eacl/grant! conn {:eacl/who  who
;                       :eacl/what what
;                       :eacl/how  :write})
;    (log/debug "Enum 3:" (eacl/enum-user-permissions @conn (:db/id who)))
;    (let [db1 @conn]
;      (is (false? (eacl/can? db1 {:eacl/who  who
;                                  :eacl/what what
;                                  :eacl/how  :read})))
;      (is (true? (eacl/can? db1 {:eacl/who  who
;                                 :eacl/what what
;                                 :eacl/how  :write})))))))

(comment)
;(clojure.test/run-tests (enum-tests))
;(let [conn     (d/create-mem-conn schema/latest-schema)
;      admin    (eacl/create-user! conn {:user/name "Test User"})
;      invoices (eacl/create-entity! conn {:eacl/path "invoices"})]
;
;  (eacl/grant! conn
;    {:eacl/who  admin
;     :eacl/how  :read
;     :eacl/what invoices})
;
;  (eacl/deny! conn
;    {:eacl/who    admin
;     :eacl/action :write
;     :eacl/what   invoices})
;
;  (d/q '[:find ?perm
;         (pull ?user [*])
;         ?action
;         (pull ?resource [*])
;         (pull ?group [*])
;         (pull ?perm [*])
;         :in
;         $ %
;         ?user ?action ?resource                          ;; how to handle group?
;         :where
;         [?perm :eacl/group ?group]                       ;; not sure
;         [?perm :eacl/resource ?resource]
;         [?perm :eacl/how :read]                          ; :read]
;         [?perm :eacl/allowed? true]                      ; true]
;         (not [?perm :eacl/allowed? false])               ;; /blocked?
;         (child-of ?user ?group)]
;    @conn
;    eacl/child-rules
;    (:db/id admin) :read (:db/id invoices))))
;(d/q '[:find
;       (pull ?user [*])
;       ?allowed
;       ?action
;       (pull ?resource [*])
;       (pull ?group [*])
;       (pull ?perm [*])
;       :in $ %
;       :where
;       ;[?perm :eacl/user ?user]
;       [?perm :eacl/resource ?resource]
;       [?perm :eacl/allowed? ?allowed]
;       [?perm :eacl/action ?action]
;       (child-of ?user ?group)]
;     @conn
;     eacl/child-rules)))

;(deftest eacl-tests
;  (let [conn eacl/conn ;; eww
;        invoices (eacl/create-entity! conn {:eacl/ident "invoices"})
;        user (eacl/create-user! conn {:user/name "Petrus Theron"})]
;    (eacl/grant-access! conn {:eacl/user user
;                              :eacl/resource (:db/id invoices)
;                              ;:eacl/path "invoices/*"
;                              :eacl/action :read})
;    (log/debug "enum:" (eacl/enum-user-permissions @conn user))
;    (is (true? (eacl/can-user? @conn {:eacl/user     user
;                                      :eacl/resource (:db/id invoices)
;                                      ;:eacl/path     "invoices/*"      ;; hmm
;                                      ;:eacl/location "Cape Town"
;                                      :eacl/action   :read})))))


(comment
  (clojure.test/run-tests))

;(enum-tests)
;(enum)

;(clojure.test/)
;(clojure.test/run-tests)
