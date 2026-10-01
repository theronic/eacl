(ns eacl.operator.folded-operator-test
  "Folded operators (EACL-FORMAL-098). The semantic DAG normalizes an
  intersection or union whose operands are one node to that node
  (`viewer & viewer` is `viewer`), while the stored expression keeps the
  operator, which the union engine refuses. Delegation once took such a
  permission for union-only and handed it to its own union plan: a check
  denied a granted resource, and lookups and counts threw a host
  ClassCastException while sealing the flattened generator.

  Each shape is paired with its unfolded twin. Every public answer, on every
  operation, must be the twin's."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]))

(def ^:private shapes
  [{:name "mutual recursion through a folded intersection (the counterexample)"
    :folded {:manage "(viewer & viewer) + access" :access "manage"}
    :twin {:manage "viewer + access" :access "manage"}}
   {:name "a folded intersection inside a recursive union"
    :folded {:view "(viewer & viewer) + parent->view"}
    :twin {:view "viewer + parent->view"}}
   {:name "an intersection of one union spelled twice"
    :folded {:view "(viewer + owner) & (owner + viewer)"}
    :twin {:view "viewer + owner"}}
   {:name "a folded intersection under an exclusion"
    :folded {:open "((viewer & viewer) + parent->open) - banned"}
    :twin {:open "(viewer + parent->open) - banned"}}
   {:name "a folded intersection beside a real one"
    :folded {:both "owner & (viewer & viewer)"}
    :twin {:both "owner & viewer"}}])

(def ^:private users ["u0" "u1" "u2"])
(def ^:private projects ["p0" "p1" "p2" "p3" "p4"])

(def ^:private relationships
  ;; [subject-type subject relation resource]: a parent chain p0 <- p1 <- p2,
  ;; a parent cycle p3 <-> p4, and grants on both.
  [[:project "p0" :parent "p1"] [:project "p1" :parent "p2"]
   [:project "p3" :parent "p4"] [:project "p4" :parent "p3"]
   [:user "u0" :viewer "p0"] [:user "u0" :owner "p1"] [:user "u0" :owner "p0"]
   [:user "u1" :viewer "p3"] [:user "u1" :banned "p4"] [:user "u1" :owner "p3"]
   [:user "u2" :owner "p2"] [:user "u2" :viewer "p2"] [:user "u2" :banned "p1"]])

(defn- schema [permissions]
  (str "definition user {}\n"
       "definition project {\n"
       "  relation parent: project\n"
       "  relation viewer: user\n"
       "  relation owner: user\n"
       "  relation banned: user\n"
       (str/join (for [[p body] (sort-by key permissions)]
                   (str "  permission " (name p) " = " body "\n")))
       "}\n"))

(defn- client [permissions]
  (let [conn (api/create-conn)
        c (api/make-client conn {:clock (constantly 1000)})]
    (eacl/write-schema! c (schema permissions))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users projects)))
    (eacl/create-relationships!
     c (vec (for [[subject-type subject relation resource] relationships]
              (eacl/->Relationship (eacl/spice-object subject-type subject) relation
                                   (eacl/spice-object :project resource)))))
    c))

(defn- outcome [f]
  (try (f)
       (catch #?(:clj Throwable :cljs :default) error
         [:thrown (or (:eacl/error (ex-data error)) (:type (ex-data error))
                      #?(:clj (.getName (class error)) :cljs (str error)))])))

(defn- walk
  "Every id of a paged walk, two at a time, as a set."
  [lookup query]
  (loop [after nil ids [] pages 0]
    (let [page (lookup (cond-> (assoc query :first 2) after (assoc :after after)))
          ids (into ids (map :id) (:data page))]
      (if (and (get-in page [:page-info :has-next-page?]) (< pages 50))
        (recur (get-in page [:page-info :end-cursor]) ids (inc pages))
        (set ids)))))

(defn- answers [c permission]
  (into {}
        (concat
         (for [u users p projects
               :let [subject (eacl/spice-object :user u) resource (eacl/spice-object :project p)]]
           [[:check u p]
            [(outcome #(:permissionship (eacl/check-permission
                                         c {:subject subject :permission permission :resource resource})))
             (outcome #(eacl/can? c {:subject subject :permission permission :resource resource}))]])
         (for [u users
               :let [query {:subject (eacl/spice-object :user u) :permission permission
                            :resource/type :project}]]
           [[:resources u]
            [(outcome #(walk (fn [q] (eacl/lookup-resources c q)) query))
             (outcome #(:count (eacl/count-resources c query)))]])
         (for [p projects
               :let [query {:resource (eacl/spice-object :project p) :permission permission
                            :subject/type :user}]]
           [[:subjects p]
            [(outcome #(walk (fn [q] (eacl/lookup-subjects c q)) query))
             (outcome #(:count (eacl/count-subjects c query)))]]))))

(deftest a-folded-operator-answers-exactly-like-its-unfolded-twin-test
  (doseq [{:keys [name folded twin]} shapes
          :let [folded-client (client folded)
                twin-client (client twin)]
          permission (sort (keys folded))]
    (testing (str name ", " (clojure.core/name permission))
      (let [expected (answers twin-client permission)
            actual (answers folded-client permission)]
        (is (not-any? #(and (vector? %) (= :thrown (first %)))
                      (mapcat val expected))
            "the twin itself answers every request")
        (is (= expected actual)
            (pr-str (into (sorted-map)
                          (keep (fn [[k v]] (when (not= v (get actual k)) [k {:twin v :folded (get actual k)}])))
                          expected)))))))

(deftest the-counterexample-grants-what-its-twin-grants-test
  ;; The minimized eacl-diff scenario s20261001-72: one viewer grant, read
  ;; through `manage` and `access`.
  (let [c (client {:manage "(viewer & viewer) + access" :access "manage"})
        subject (eacl/spice-object :user "u0")]
    (doseq [permission [:manage :access]]
      (testing (name permission)
        (is (= :has-permission
               (:permissionship (eacl/check-permission
                                 c {:subject subject :permission permission
                                    :resource (eacl/spice-object :project "p0")}))))
        (is (= #{"p0"} (set (map :id (:data (eacl/lookup-resources
                                             c {:subject subject :permission permission
                                                :resource/type :project :first 10}))))))
        (is (= 1 (:count (eacl/count-resources c {:subject subject :permission permission
                                                  :resource/type :project}))))))))
