(ns eacl.datascript.relationship-observation-gate-test
  "Non-authoritative relationship observations must cost nothing unless a
  client opts in: no store allocation at construction, and no recording
  work — including observation key construction — on the page, count, and
  membership paths of a default-constructed client."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.metrics :as metrics]))

(def ^:private schema
  "definition user {}
   definition account {
     relation owner: user
     relation banned: user
     permission admin = owner - banned
   }")

(defn- seeded-client
  [config-opts]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn config-opts)
        users (mapv #(eacl/spice-object :user (str "user-" %)) (range 4))
        accounts (mapv #(eacl/spice-object :account (str "account-" %))
                       (range 4))]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id (:id %))
                             (into users accounts)))
    (eacl/create-relationships!
     client
     (vec (for [user users
                account accounts]
            (eacl/->Relationship user :owner account))))
    {:client client
     :user (first users)
     :account (first accounts)}))

(defn- drive-request-paths!
  [{:keys [client user account]}]
  (is (true? (eacl/can? client user :admin account)))
  (is (= 4 (count (:data (eacl/lookup-resources
                          client {:subject user
                                  :resource/type :account
                                  :permission :admin
                                  :first 10})))))
  (is (= {:count 4}
         (select-keys (eacl/count-resources
                       client {:subject user
                               :resource/type :account
                               :permission :admin})
                      [:count]))))

(deftest default-client-performs-no-observation-work-test
  (let [store-allocations (atom 0)
        key-constructions (atom 0)
        original-make-store metrics/make-store
        original-key metrics/observation-key
        env
        (with-redefs [metrics/make-store
                      (fn []
                        (swap! store-allocations inc)
                        (original-make-store))]
          (seeded-client {}))]
    (is (zero? @store-allocations)
        "a default client allocates no observation store")
    (with-redefs [metrics/observation-key
                  (fn [context descriptor direction]
                    (swap! key-constructions inc)
                    (original-key context descriptor direction))]
      (drive-request-paths! env))
    (is (zero? @key-constructions)
        "no observation key is constructed on page, count, or membership paths")
    (is (true? (:disabled?
                (:relationship-observations
                 (datascript/cache-stats (:client env))))))))

(deftest opted-in-client-records-observations-test
  (let [env (seeded-client {:relationship-observations? true})]
    (drive-request-paths! env)
    (let [stats (:relationship-observations
                 (datascript/cache-stats (:client env)))]
      (is (nil? (:disabled? stats)))
      (is (pos? (:recorded-events stats))
          "opting in records observations on the same request paths"))))

(deftest relationship-observation-option-is-closed-and-boolean-test
  (doseq [value [nil 0 "true" :enabled]]
    (let [data
          (try
            (datascript/make-client
             (datascript/create-conn)
             {:relationship-observations? value})
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) error
              (ex-data error)))]
      (is (= :eacl/invalid-config (:type data)) (pr-str value))
      (is (= :relationship-observations? (:key data)) (pr-str value)))))

(deftest recording-prefers-evicting-superseded-watermarks-test
  ;; When the store is full, entries whose watermark can never recur are the
  ;; preferred victims, so entries that remain reusable at the current
  ;; watermark stay resident on a write-active source.
  (let [store (metrics/make-store)
        context (fn [watermark]
                  {:backend :gate-test
                   :source-id "source"
                   :branch nil
                   :source-lifecycle "lifecycle"
                   :high-watermark watermark})
        descriptor (fn [index] {:endpoint index})]
    (dotimes [index metrics/maximum-entries]
      (metrics/record-exhausted!
       store (context 1) (descriptor index) :forward 1 nil))
    (dotimes [index 3]
      (metrics/record-exhausted!
       store (context 2) (descriptor index) :forward 2 nil))
    (let [entries (:entries @store)]
      (is (= metrics/maximum-entries (count entries)))
      (is (every?
           #(contains? entries
                       (metrics/observation-key
                        (context 2) (descriptor %) :forward))
           (range 3))
          "current-watermark entries survive the overflow"))))
