(ns eacl.examples.caveats
  "Executable v9 guide. Load through the project nREPL with :caveats-jvm."
  (:require [datascript.core :as ds]
            [eacl.caveats.jvm :as caveats]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]))

(def schema
  "caveat in_region(region string, accepted list<string>) {
     region in accepted
   }
   definition user {}
   definition doc {
     relation viewer: user | user with in_region
     relation banned: user
     permission view = viewer - banned
   }")

(defn error-type [operation]
  (try (operation) nil
       (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn run-example! []
  (let [conn (api/create-conn)
        now (atom 1000)
        client (api/make-client conn {:clock #(deref now) :caveat-evaluator (caveats/evaluator)})
        alice (eacl/spice-object :user "alice")
        report (eacl/spice-object :doc "report")
        other (eacl/spice-object :doc "other")
        relationship {:subject alice :relation :viewer :resource report
                      :caveat "in_region" :caveat-context {"accepted" ["za"]}
                      :valid-until-ms 2000}
        request {:subject alice :resource report :permission :view}
        with-region (assoc request :caveat-context {"region" "za"})]
    (eacl/write-schema! client schema)
    (ds/transact! conn [{:eacl/id "alice"} {:eacl/id "report"} {:eacl/id "other"}])
    (eacl/create-relationship! client relationship)
    (eacl/create-relationship! client alice :viewer other)
    (let [conditional (eacl/check-permission client request)]
      (assert (= :conditional-permission (:permissionship conditional)))
      (assert (= ["region"] (:missing-fields conditional)))
      (assert (false? (eacl/can? client request))))
    (assert (eacl/can? client with-region))
    (assert (false? (eacl/can? client (assoc request :caveat-context {"region" "us"}))))
    (let [query {:subject alice :resource/type :doc :permission :view}
          detailed (eacl/count-resources client (assoc query :result-policy :detailed))]
      (assert (= 1 (:count (eacl/count-resources client query))))
      (assert (= [2 1 1] ((juxt :count :definite-count :conditional-count) detailed))))
    (eacl/create-relationship! client (assoc (eacl/->Relationship alice :banned report) :valid-until-ms 1500))
    (assert (false? (eacl/can? client with-region)))
    (reset! now 1500)
    (assert (eacl/can? client with-region) "An expired ban disappears without a write")
    (let [pinned (eacl/snapshot client)
          query {:subject alice :resource/type :doc :permission :view :first 1
                 :caveat-context {"region" "za"}}
          page (eacl/lookup-resources client query)
          cursor (get-in page [:page-info :end-cursor])]
      (try
        (assert (= ["report"] (mapv :id (:data page))))
        (reset! now 2000)
        (assert (false? (eacl/can? client with-region)))
        (assert (eacl/can? pinned with-region) "Pinned time is intentionally historical")
        (assert (= :eacl.pagination/restart-required
                   (error-type #(eacl/lookup-resources client (assoc query :after cursor)))))
        (assert (= ["other"] (mapv :id (:data (eacl/lookup-resources client query)))))
        (finally (eacl/release! pinned))))
    (let [query {:resource/type :doc :resource/id "report" :first 20}
          stored (eacl/read-relationships client query)
          active (eacl/read-relationships client (assoc query :relationship-state :expiry-active))]
      (assert (= 2 (count (:data stored))))
      (assert (empty? (:data active))))
    (assert (= :eacl/relationship-conflict (error-type #(eacl/create-relationship! client relationship))))
    (eacl/write-relationship! client (assoc relationship :operation :touch :valid-until-ms 3000))
    (assert (eacl/can? client with-region))
    ;; A caller-managed prepared handle is inert until the composed native tx.
    (let [prepared-value (assoc relationship :valid-until-ms 4000)
          prepared (eacl/prepare-relationship! client prepared-value)
          snapshot (eacl/snapshot client)]
      (try
        (ds/transact! conn (eacl/tx-relationships snapshot
                                                  [{:operation :touch :relationship prepared-value
                                                    :prepared-qualifier prepared}]))
        (finally (eacl/release! snapshot))))
    (eacl/delete-relationship! client relationship)
    (assert (false? (eacl/can? client with-region)))
    {:conditional :conditional-permission :ban-expiry :granted :grant-expiry :denied
     :pinned :historical-grant :live-cursor :restart-required :renewal :granted :delete :denied}))
