(ns eacl.scan-cache-fixture
  "The sparse high-sharing fixture behind the scan-response cache gates,
  built through the public write API so every backend seeds identical
  relationships: `users` users, `groups` groups, each user a member of
  `groups-per-user` pseudo-random groups, each group owning zero to three
  docs (an `empty-fraction` of groups own none)."
  (:require [eacl.core :as eacl]))

(def schema
  "definition user {}
   definition group {
     relation member: user
   }
   definition doc {
     relation group: group
     permission view = group->member
   }")

(defn- lcg
  "A deterministic generator (Park-Miller minimal standard): returns a fn
  of a bound. Every intermediate stays below 2^47, so the sequence is
  identical on the JVM and in JavaScript doubles."
  [seed]
  (let [state (atom (inc (mod seed 2147483646)))]
    (fn [bound]
      (let [value (mod (* 48271 @state) 2147483647)]
        (reset! state value)
        (mod (quot value 256) bound)))))

(defn group [g] (eacl/spice-object :group (str "grp-" g)))
(defn doc [g i] (eacl/spice-object :doc (str "doc-" g "-" i)))
(defn user [u] (eacl/spice-object :user (str "user-" u)))

(defn group-docs
  "Map from group index to the doc indexes it owns."
  [{:keys [groups empty-fraction seed] :or {empty-fraction 0.6 seed 42}}]
  (let [next-int (lcg seed)
        empty-threshold (long (* 1000 empty-fraction))]
    (into {}
          (for [g (range groups)]
            [g (if (< (next-int 1000) empty-threshold)
                 []
                 (range (inc (next-int 3))))]))))

(defn objects
  "Every object id the fixture references, for backends that require
  objects to exist before relationships reference them."
  [{:keys [users groups] :as config}]
  (let [group-docs (group-docs config)]
    (concat (for [g (range groups)] (str "grp-" g))
            (for [g (range groups) i (get group-docs g)] (str "doc-" g "-" i))
            (for [u (range users)] (str "user-" u)))))

(defn memberships
  "Map from user index to the group indexes the user belongs to."
  [{:keys [users groups groups-per-user seed] :or {seed 42}}]
  (let [next-int (lcg (+ seed 7))]
    (into {}
          (for [u (range users)]
            [u (vec (distinct (repeatedly (* 2 groups-per-user)
                                          #(next-int groups))))]))))

(defn seed!
  "Writes the schema and relationships into `client`. `ensure-objects!`,
  when supplied, receives the vector of object ids to create first."
  [client {:keys [users groups] :as config} ensure-objects!]
  (let [config (merge {:groups-per-user 10} config)
        docs (group-docs config)
        members (memberships config)]
    (eacl/write-schema! client schema)
    (when ensure-objects!
      (ensure-objects! (vec (objects config))))
    (doseq [batch (partition-all 100 (range groups))]
      (let [relationships
            (vec (for [g batch i (get docs g)]
                   (eacl/->Relationship (group g) :group (doc g i))))]
        (when (seq relationships)
          (eacl/create-relationships! client relationships))))
    (doseq [batch (partition-all 20 (range users))]
      (eacl/create-relationships!
       client
       (vec (for [u batch
                  g (take (:groups-per-user config) (get members u))]
              (eacl/->Relationship (user u) :member (group g))))))
    {:relationship-count
     (+ (reduce + (map count (vals docs)))
        (reduce + (map #(count (take (:groups-per-user config) %))
                       (vals members))))}))

(defn page
  [client u n & {:keys [cache? after] :or {cache? true}}]
  (eacl/lookup-resources
   client
   (cond-> {:subject (user u) :permission :view :resource/type :doc
            :first n :cache? cache?}
     after (assoc :after after))))
