(ns eacl.caveats.permission-tree-contract
  "Cross-backend contract for `expand-permission-tree` over caveated and
  expiring Relationships (EACL-FORMAL-081).

  The tree lists every stored Relationship. A leaf subject, or an arrow child
  node, reached through a qualified Relationship carries that Relationship's
  `:caveat`, `:caveat-context` (omitted when empty), and `:valid-until-ms`,
  as `read-relationships` renders them. Nothing is evaluated, so the tree does
  not change when a deadline passes."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(def schema
  (str "caveat enabled(flag bool) { flag }\n"
       "definition user {}\n"
       "definition folder {\n"
       " relation viewer: user | user with enabled\n"
       " permission view = viewer\n"
       "}\n"
       "definition doc {\n"
       " relation viewer: user | user with enabled\n"
       " relation banned: user\n"
       " relation folder: folder\n"
       " permission view = viewer + folder->view\n"
       " permission guarded = (viewer + folder->view) - banned\n"
       "}"))

(defn- object [type id] (eacl/spice-object type (str "tree/" id)))

(def ^:private doc (object :doc "d"))
(def ^:private folder-f (object :folder "f"))
(def ^:private folder-g (object :folder "g"))

(def ^:private relationships
  [(assoc (eacl/->Relationship (object :user "a") :viewer doc) :valid-until-ms 5000)
   (eacl/->Relationship (object :user "b") :viewer doc)
   (assoc (eacl/->Relationship (object :user "c") :viewer doc)
          :caveat "enabled" :caveat-context {"flag" true})
   (assoc (eacl/->Relationship (object :user "e") :viewer doc) :caveat "enabled")
   (eacl/->Relationship (object :user "b") :banned doc)
   (assoc (eacl/->Relationship folder-f :folder doc) :valid-until-ms 7000)
   (eacl/->Relationship folder-g :folder doc)
   (eacl/->Relationship (object :user "t") :viewer folder-f)
   (assoc (eacl/->Relationship (object :user "s") :viewer folder-g)
          :caveat "enabled" :valid-until-ms 6000)])

(defn- annotated
  "The stored subject of a Relationship, with its qualifier keys."
  [relationship]
  (merge (:subject relationship)
         (select-keys relationship [:caveat :caveat-context :valid-until-ms])))

(defn- normalized
  "Subject and union-child order follows internal eids, which differ by
  backend; exclusion children keep their directed order."
  [tree]
  (cond
    (:leaf tree)
    (update-in tree [:leaf :subjects] #(vec (sort-by :id %)))

    (= :exclusion (get-in tree [:intermediate :operation]))
    (update-in tree [:intermediate :children] #(mapv normalized %))

    :else
    (update-in tree [:intermediate :children]
               #(vec (sort-by (fn [child]
                                [(get-in child [:expanded-object :id])
                                 (name (:expanded-relation child))])
                              (map normalized %))))))

(def ^:private doc-viewer-leaf
  {:expanded-object doc
   :expanded-relation :viewer
   :leaf {:subjects (mapv annotated (take 4 relationships))}})

(defn- folder-view
  [folder subjects]
  {:expanded-object folder
   :expanded-relation :view
   :intermediate {:operation :union
                  :children [{:expanded-object folder
                              :expanded-relation :viewer
                              :leaf {:subjects subjects}}]}})

(defn- arrow-children
  [permission]
  {:expanded-object doc
   :expanded-relation permission
   :intermediate
   {:operation :union
    :children
    [(assoc (folder-view folder-f [(object :user "t")]) :valid-until-ms 7000)
     (folder-view folder-g [(annotated (peek relationships))])]}})

(def ^:private expected-trees
  {:viewer doc-viewer-leaf
   :view {:expanded-object doc
          :expanded-relation :view
          :intermediate {:operation :union
                         :children [doc-viewer-leaf (arrow-children :view)]}}
   :guarded {:expanded-object doc
             :expanded-relation :guarded
             :intermediate
             {:operation :exclusion
              :children
              [{:expanded-object doc
                :expanded-relation :guarded
                :intermediate {:operation :union
                               :children [doc-viewer-leaf (arrow-children :guarded)]}}
               {:expanded-object doc
                :expanded-relation :banned
                :leaf {:subjects [(object :user "b")]}}]}}})

(defn- expand
  [client permission & {:as options}]
  (:tree-root (eacl/expand-permission-tree
               client (merge {:resource doc :permission permission} options))))

(defn check!
  "`env` carries the qualified `:client`, its `:writer`, and the `:now` clock
  atom, as for `eacl.caveats.inspection-contract/check!`."
  [{:keys [client writer now]}]
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (eacl/write-schema! client schema)
    (let [native (:native (writer))]
      ((:transact! native)
       (mapv #(hash-map :eacl/id (str "tree/" %))
             ["a" "b" "c" "e" "s" "t" "f" "g" "d"]))
      (reset! now 1000)
      (eacl/write-relationships!
       client (mapv #(hash-map :operation :create :relationship %) relationships))
      (let [before (into {}
                         (map (fn [permission] [permission (expand client permission)]))
                         (keys expected-trees))]
        (doseq [[permission expected] expected-trees]
          (testing (str "expand " permission " lists stored rows with their qualifiers")
            (is (= (normalized expected) (normalized (get before permission))))))
        (testing "an expired grant and an expired arrow edge stay in the tree"
          (reset! now 10000)
          (is (false? (eacl/can? client (object :user "a") :view doc)))
          (is (false? (eacl/can? client (object :user "t") :view doc)))
          (doseq [permission (keys expected-trees)]
            (is (= (get before permission) (expand client permission)))
            (is (= (get before permission) (expand client permission :cache? false)))))
        (testing "an absent root expands to an empty leaf without scanning"
          (is (= {:expanded-object (object :doc "absent")
                  :expanded-relation :viewer
                  :leaf {:subjects []}}
                 (:tree-root (eacl/expand-permission-tree
                              client {:resource (object :doc "absent")
                                      :permission :viewer})))))))))
