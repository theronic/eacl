(ns eacl.wildcard-reference
  "An independent reference for wildcard subjects and seeded stores for the
  randomized differential test.

  It follows formal/dafny/WildcardSubjects.dfy: a concrete subject is a
  member of a relation through its own relationship or, when the relation
  declares `user:*`, through the wildcard's; the wildcard's own decision uses
  its relationships only. It evaluates `schema` directly over a set of
  relationship tuples, with the recursive `folder.view` as a least fixed
  point, and shares no code with EACL's engines.")

(def schema
  "definition user {}
definition group {
  relation member: user | user:*
}
definition folder {
  relation parent: folder
  relation viewer: user | user:*
  relation banned: user
  relation owner: user
  permission view = viewer + parent->view
  permission clean = view - banned
  permission owned_view = view & owner
}
definition doc {
  relation folder: folder
  relation reader: user | user:*
  relation blocked: user | user:*
  relation editor: user
  relation team: group
  permission read = reader + folder->view + team->member
  permission safe_read = read - blocked
  permission edit = editor & read
  permission strict = (reader - blocked) & folder->clean
  permission any_clean = folder->clean + editor
}")

(def wildcard-relations
  #{[:group :member] [:folder :viewer] [:doc :reader] [:doc :blocked]})

(def users ["u0" "u1" "u2" "u3" "u4"])
(def unmentioned-user
  "An existing user no relationship names: it holds what the wildcard holds."
  "zz")
(def groups ["g0" "g1"])
(def folders ["f0" "f1" "f2" "f3"])
(def docs ["d0" "d1" "d2" "d3" "d4"])

(def permissions
  {:folder [:view :clean :owned_view]
   :doc [:read :safe_read :edit :strict :any_clean]})

(def objects
  (vec (concat users [unmentioned-user] groups folders docs)))

(def ^:private rng-modulus 2147483647)
(def ^:private rng-multiplier 48271)

(defn- next-state [state]
  (mod (* (max 1 state) rng-multiplier) rng-modulus))

(defn- draws
  "An infinite sequence of values in [0, 100) from `seed`."
  [seed]
  (map #(mod % 100) (rest (iterate next-state (inc seed)))))

(defn store
  "A seeded set of `[subject relation resource]` tuples, objects as
  `[type id]`. The folder hierarchy may contain cycles."
  [seed]
  (let [slots (concat
               (for [g groups s (conj users "*")] [[:user s] :member [:group g] 30])
               (for [f folders s (conj users "*")] [[:user s] :viewer [:folder f] 22])
               (for [f folders s users] [[:user s] :banned [:folder f] 20])
               (for [f folders s users] [[:user s] :owner [:folder f] 20])
               (for [f folders p folders :when (not= f p)] [[:folder p] :parent [:folder f] 18])
               (for [d docs f folders] [[:folder f] :folder [:doc d] 22])
               (for [d docs s (conj users "*")] [[:user s] :reader [:doc d] 18])
               (for [d docs s users] [[:user s] :blocked [:doc d] 15])
               (for [d docs] [[:user "*"] :blocked [:doc d] 8])
               (for [d docs s users] [[:user s] :editor [:doc d] 22])
               (for [d docs g groups] [[:group g] :team [:doc d] 25]))]
    (into #{}
          (keep (fn [[[subject relation resource percent] draw]]
                  (when (< draw percent) [subject relation resource])))
          (map vector slots (draws seed)))))

(defn- member?
  [store [subject-type subject-id :as subject] relation [resource-type :as resource]]
  (or (contains? store [subject relation resource])
      (and (not= "*" subject-id)
           (contains? wildcard-relations [resource-type relation])
           (contains? store [[subject-type "*"] relation resource]))))

(defn- targets
  "The resources of `target-type` that `relation` on `resource` holds."
  [store relation resource target-type]
  (keep (fn [[[subject-type subject-id] tuple-relation tuple-resource]]
          (when (and (= relation tuple-relation) (= resource tuple-resource)
                     (= target-type subject-type))
            subject-id))
        store))

(defn- viewable-folders
  "Least fixed point of `view = viewer + parent->view` for `subject`."
  [store subject]
  (loop [viewable (set (filter #(member? store subject :viewer [:folder %]) folders))]
    (let [next (into viewable
                     (filter (fn [folder]
                               (some viewable (targets store :parent [:folder folder] :folder))))
                     folders)]
      (if (= next viewable) viewable (recur next)))))

(defn permitted?
  "Whether `subject` (`[:user id]`, or `[:user \"*\"]` for the wildcard's own
  decision) holds `permission` on `[type id]`."
  [store subject permission [resource-type resource-id :as resource]]
  (let [viewable (viewable-folders store subject)
        member #(member? store subject %1 %2)
        view? #(contains? viewable %)
        clean? #(and (view? %) (not (member :banned [:folder %])))
        doc-folders #(targets store :folder [:doc %] :folder)
        read? (fn [doc]
                (boolean
                 (or (member :reader [:doc doc])
                     (some view? (doc-folders doc))
                     (some #(member :member [:group %]) (targets store :team [:doc doc] :group)))))]
    (boolean
     (case [resource-type permission]
       [:folder :view] (view? resource-id)
       [:folder :clean] (clean? resource-id)
       [:folder :owned_view] (and (view? resource-id) (member :owner resource))
       [:doc :read] (read? resource-id)
       [:doc :safe_read] (and (read? resource-id) (not (member :blocked resource)))
       [:doc :edit] (and (member :editor resource) (read? resource-id))
       [:doc :strict] (and (member :reader resource)
                           (not (member :blocked resource))
                           (some clean? (doc-folders resource-id)))
       [:doc :any_clean] (or (some clean? (doc-folders resource-id))
                             (member :editor resource))))))
