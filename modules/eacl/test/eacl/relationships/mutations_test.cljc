(ns eacl.relationships.mutations-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.context-test :as errors]
            [eacl.caveats.values :as values]
            [eacl.relationships.mutations :as mutations]
            [eacl.relationships.staged :as staged]))

(def relationship {:subject {:type :user :id "u"}
                   :relation :member :resource {:type :folder :id "f"}})

(deftest application-composition-protects-nested-native-entity-maps
  (let [safe [{:db/id 1 :app/value {"nested" [1 2]}}
              [:db/add 1 :app/reference {:db/id 2 :app/flag true}]]]
    (is (= safe (staged/application-datoms safe #{99}))))
  (doseq [tx [[{:db/id 1 :app/reference {:db/id 99 :app/flag true}}]
              [[:db/add 1 :app/reference {:db/id 99 :app/flag true}]]
              [{:db/id 1 :app/references [{:eacl.relationship-qualifier/valid-until-ms 100}]}]
              [[:db/add 1 :app/reference {:db/id "hidden" :eacl.relation/relation-name :viewer}]]]]
    (is (= :application-datoms
           (:reason (errors/error-data #(staged/application-datoms tx #{99})))))))

(deftest ordinary-write-normalization-retains-the-original-relationship
  (is (identical? relationship (mutations/normalize-relationship relationship)))
  (is (= relationship (mutations/normalize-relationship
                       (assoc relationship :caveat nil :valid-until-ms nil))))
  (is (= (assoc relationship :caveat "enabled")
         (mutations/normalize-relationship (assoc relationship :caveat "enabled" :caveat-context {})))))

(deftest qualified-write-input-is-one-named-caveat-with-bounded-context-and-expiry
  (let [qualified (assoc relationship :caveat "enabled" :caveat-context {"flag" true}
                         :valid-until-ms 100)]
    (is (= qualified (mutations/normalize-relationship qualified)))
    (doseq [fields [{:caveat ["a" "b"]} {:caveat :enabled} {:caveat ""}
                    {:caveat {"enabled" {}}} {:caveat-context {"flag" true}}
                    {:caveat "enabled" :caveat-context false}
                    {:caveat "enabled" :caveat-context {"flag" 1.5}}
                    {:caveat "enabled" :caveat-context {(apply str (repeat 65 "a")) true}}
                    {:caveat "enabled" :caveat-context {"flag" (apply str (repeat 4097 "a"))}}
                    {:valid-until-ms (dec (:timestamp-min-ms values/limits))}
                    {:valid-until-ms 1.5} {:valid-until-ms "100"}
                    {:valid-until-ms 9007199254740992} {:qualifier-eid 123}]]
      (is (some? (errors/error-data #(mutations/normalize-relationship (merge relationship fields))))))))

(deftest batch-identity-excludes-qualifiers-but-update-intent-does-not
  (let [a (assoc relationship :caveat "enabled" :caveat-context {"flag" true} :valid-until-ms 100)
        b (assoc a :valid-until-ms 200)
        update-a {:operation :touch :relationship a}
        update-b {:operation :touch :relationship b}]
    (is (= [update-a] (mutations/normalize-updates [update-a update-a])))
    (doseq [updates [[update-a update-b]
                     [update-a (assoc update-a :operation :create)]
                     [(assoc update-a :operation :create) (assoc update-b :operation :create)]]]
      (is (= :eacl/invalid-relationship-update-batch
             (:type (errors/error-data #(mutations/normalize-updates updates))))))
    (is (= 1 (count (mutations/normalize-updates
                     [{:operation :delete :relationship a} {:operation :delete :relationship b}]))))
    (is (= [{:operation :touch :relationship relationship}]
           (mutations/normalize-updates
            [{:operation :touch :relationship relationship}
             {:operation :touch :relationship (assoc relationship :caveat nil :valid-until-ms nil)}])))))

(deftest cached-qualifier-metadata-must-be-closed-bounded-and-canonical
  (doseq [metadata [{} {:caveat "enabled"} {:valid-until-ms 100}
                    {:caveat "enabled" :caveat-context {"flag" false} :valid-until-ms 100}]]
    (is (mutations/canonical-qualifier-metadata? (merge relationship metadata))))
  (doseq [metadata [{:caveat nil} {:caveat ""} {:valid-until-ms nil}
                    {:valid-until-ms 253402300800000} {:valid-until-ms "100"}
                    {:caveat-context {"flag" true}}
                    {:caveat "enabled" :caveat-context {}}
                    {:caveat "enabled" :caveat-context nil}]]
    (is (false? (mutations/canonical-qualifier-metadata? (merge relationship metadata))))))
