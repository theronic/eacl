(ns eacl.datascript.wildcard-differential-test
  "Seeded differential of EACL's wildcard answers against
  eacl.wildcard-reference, which follows formal/dafny/WildcardSubjects.dfy.
  Every seed is a random store over one schema with wildcard relations under
  union, intersection, exclusion (on both sides), arrows to relations and to
  permissions, and a recursive folder hierarchy that may contain cycles."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.wildcard-reference :as reference]))

(def ^:private seeds (range #?(:clj 40 :cljs 8)))

(defn- ->object [[type id]] (eacl/spice-object type id))

(defn- walk
  [f query]
  (loop [query query items [] guard 0]
    (let [page (f query)
          items (into items (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< guard 200))
        (recur (assoc query :after (get-in page [:page-info :end-cursor])) items (inc guard))
        items))))

(defn- granted-by-listing
  "The users a subject listing grants: its own entry or, unless excluded,
  the `*` entry."
  [entries]
  (let [by-id (into {} (map (juxt :id identity)) entries)
        excluded (set (map :id (:excluded-subjects (by-id "*"))))]
    (set (filter #(or (contains? by-id %)
                      (and (contains? by-id "*") (not (excluded %))))
                 (conj reference/users reference/unmentioned-user)))))

(defn- client-for
  [store]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (ds/transact! conn (mapv (fn [id] {:eacl/id id}) reference/objects))
    (eacl/write-schema! client reference/schema)
    (eacl/create-relationships!
     client (mapv (fn [[subject relation resource]]
                    (eacl/->Relationship (->object subject) relation (->object resource)))
                  store))
    client))

(defn- assert-seed!
  [seed]
  (let [store (reference/store seed)
        client (client-for store)
        all-users (conj reference/users reference/unmentioned-user)]
    (doseq [[resource-type permissions] reference/permissions
            permission permissions
            resource-id (if (= :doc resource-type) reference/docs reference/folders)
            :let [resource [resource-type resource-id]
                  expected (set (filter #(reference/permitted? store [:user %] permission resource)
                                        all-users))
                  wildcard? (reference/permitted? store [:user "*"] permission resource)]]
      (testing (str "seed " seed " " permission " on " resource-id)
        (is (= expected
               (set (filter #(eacl/can? client (eacl/spice-object :user %) permission
                                        (->object resource))
                            all-users)))
            "checks")
        (is (= (mapv #(contains? expected %) all-users)
               (mapv :allowed?
                     (eacl/check-permissions
                      client {:checks (mapv (fn [user]
                                              {:subject (eacl/spice-object :user user)
                                               :permission permission
                                               :resource (->object resource)})
                                            all-users)})))
            "batched checks")
        (let [entries (walk #(eacl/lookup-subjects client %)
                            {:resource (->object resource) :permission permission
                             :subject/type :user :first 2})
              ids (map :id entries)]
          (is (= expected (granted-by-listing entries)) "subject listing")
          (is (= wildcard? (contains? (set ids) "*")) "the * entry")
          (is (every? #(or (= "*" %) (contains? expected %)) ids)
              "only granted subjects are listed")
          (is (= (count ids) (count (distinct ids))) "no entry repeats")
          (is (= (count entries)
                 (:count (eacl/count-subjects
                          client {:resource (->object resource) :permission permission
                                  :subject/type :user})))
              "count-subjects counts entries"))))
    (doseq [user all-users
            [resource-type permissions] reference/permissions
            permission permissions
            :let [candidates (if (= :doc resource-type) reference/docs reference/folders)
                  expected (set (filter #(reference/permitted? store [:user user] permission
                                                               [resource-type %])
                                        candidates))
                  query {:subject (eacl/spice-object :user user) :permission permission
                         :resource/type resource-type}]]
      (testing (str "seed " seed " " user " " permission)
        (is (= expected (set (map :id (walk #(eacl/lookup-resources client %)
                                            (assoc query :first 2)))))
            "lookup-resources")
        (is (= (count expected) (:count (eacl/count-resources client query)))
            "count-resources")))))

(deftest wildcard-answers-match-the-reference
  (doseq [seed seeds]
    (assert-seed! seed)))
