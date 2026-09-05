(ns eacl.caveats.cache-trace-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(defn schema [expression]
  (str "caveat enabled(flag bool) { " expression " }\n"
       "definition user {}\n"
       "definition doc {\n"
       " relation member: user | user with enabled\n"
       " relation writer: user\n"
       " relation banned: user\n"
       " relation parent: doc\n"
       " permission direct = member\n"
       " permission view = member - banned\n"
       " permission walk = member + parent->walk\n"
       " permission via = parent->direct\n"
       " permission either = member + writer\n}"))

(defn outcome [f]
  (try (f)
       (catch #?(:clj Exception :cljs :default) error
         {:fault (select-keys (ex-data error) [:type :reason :eacl/error])})))

(defn drain [operation client query]
  (loop [boundary nil pages 0 values []]
    (when (> pages 20) (throw (ex-info "Cache trace lookup did not progress" {:type ::no-progress})))
    (let [page (operation client (cond-> (assoc query :first 1) boundary (assoc :after boundary)))
          values (into values (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) (inc pages) values)
        values))))

(defn compare-operation! [label read]
  (let [cold (outcome #(read {:cache? false}))
        warm (outcome #(read {}))
        repeated (outcome #(read {}))
        read-only (outcome #(read {:populate-cache? false}))]
    (is (not (:fault cold)) (pr-str label))
    (is (= cold warm repeated read-only) (pr-str label))
    cold))

(defn sample! [client subject resource label]
  (doseq [context [{} {"flag" true} {"flag" false}]
          permission [:direct :view :walk :via :either]]
    (let [check {:subject subject :resource resource :permission permission :caveat-context context}]
      (compare-operation! [label :point permission context]
                          #(dissoc (eacl/check-permission client (merge check %)) :cached? :cache-basis :evaluation))
      (doseq [direction [:forward :reverse] policy [:definite :detailed]]
        (let [query (merge {:permission permission :caveat-context context :result-policy policy
                            :evaluation :complete-denotation}
                           (if (= :forward direction)
                             {:subject subject :resource/type :doc}
                             {:resource resource :subject/type :user}))
              lookup (if (= :forward direction) eacl/lookup-resources eacl/lookup-subjects)
              count! (if (= :forward direction) eacl/count-resources eacl/count-subjects)]
          (compare-operation! [label :lookup direction policy permission context]
                              #(drain lookup client (merge query %)))
          (doseq [limit [nil 1]]
            (compare-operation! [label :count direction policy permission context limit]
                                #(dissoc (count! client (cond-> (merge query %) limit (assoc :count-limit limit)))
                                         :cached? :cache-basis))))))))

(defn check! [{:keys [client writer now expire-cache! rotate-client!]}]
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (eacl/write-schema! client {:schema (schema "flag")})
    (let [active-client (atom client)
          native (:native (writer))
          subject (eacl/spice-object :user "trace/u")
          resources (mapv #(eacl/spice-object :doc %) ["trace/a" "trace/b" "trace/c"])
          [a b c] resources
          relationship #(eacl/->Relationship subject %1 %2)
          member (relationship :member a)
          ban (relationship :banned a)
          touch! #(eacl/write-relationships! @active-client [{:operation :touch :relationship %}])
          point #(get (eacl/check-permission @active-client {:subject subject :resource a :permission %
                                                             :caveat-context {"flag" true}}) :permissionship)
          sample #(sample! @active-client subject a %)]
      ((:transact! native) (mapv #(hash-map :eacl/id (:id %)) (into [subject] resources)))
      (eacl/write-relationships! @active-client
                                 (mapv #(hash-map :operation :create :relationship %)
                                       [(assoc member :caveat "enabled" :valid-until-ms 200)
                                        (assoc (relationship :member b) :caveat "enabled" :caveat-context {"flag" false} :valid-until-ms 200)
                                        (relationship :member c) (relationship :writer a)
                                        (assoc ban :valid-until-ms 100)
                                        (eacl/->Relationship b :parent a)
                                        (eacl/->Relationship c :parent b)]))
      (sample :initial)
      (is (= :no-permission (point :view)))
      (reset! now 100)
      (sample :expired-ban)
      (is (= :has-permission (point :view)))
      (reset! now 200)
      (sample :expired-grant)
      (is (= :no-permission (point :direct)))
      (touch! (assoc member :caveat "enabled" :caveat-context {"flag" true} :valid-until-ms 300))
      (sample :renewed-bound-context)
      (is (= :has-permission (point :direct)))
      (eacl/write-schema! @active-client {:schema (schema "!flag")})
      (sample :changed-caveat-definition)
      (is (= :no-permission (point :direct)))
      (if rotate-client!
        (reset! active-client (rotate-client! @active-client "qualified-cache-trace-reset"))
        (expire-cache! @active-client "qualified-cache-trace-reset"))
      (sample :new-source-lifecycle)
      (touch! (assoc ban :valid-until-ms 250))
      (touch! member)
      (sample :removed-qualifier)
      (is (= :has-permission (point :direct)))
      (is (= :no-permission (point :view)))
      (reset! now 250)
      (sample :second-ban-expiry)
      (is (= :has-permission (point :view)))
      {:subject subject :resource a :member member :ban ban})))
