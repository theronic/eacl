(ns eacl.exploration.cursor-refinement-bridge
  "Exploration-only executable bridge for the minimum public edge cursor.

  The token authenticates one exact semantic/order/basis context and one Relay
  edge.  Navigation mode and private checkpoint identity are intentionally not
  fields.  This bridge exercises the existing portable secure-format service;
  it does not modify the production Relay codec."
  (:require [clojure.walk :as walk]
            [eacl.secure-format :as secure-format]))

(def ^:private maximum-token-size 8192)
(def ^:private maximum-page-size 1000)
(def ^:private cursor-domain "eacl.exploration.edge-cursor.v1")
(def ^:private cursor-prefix "eacl_edge_v1_")
(def ^:private checkpoint-domain "eacl.exploration.checkpoint-key.v1")
(def ^:private payload-keys #{:format :context :edge :expires-at})
(def ^:private private-keys
  #{:checkpoint :checkpoint-key :frontier :admitted :grant-set :relationships
    :cached-response :exception :state :continuation})

(def key-one (vec (range 1 33)))
(def key-two (vec (range 33 65)))

(defn- codec-options
  ([] (codec-options :k2 {:k1 key-one :k2 key-two}))
  ([current-kid keyring]
   {:domain cursor-domain
    :prefix cursor-prefix
    :current-kid current-kid
    :keyring keyring
    :maximum-size maximum-token-size
    :maximum-depth 32
    :maximum-entries 512
    :payload-keys payload-keys}))

(def base-context
  {:semantic-abi 8
   :reducer-abi 2
   :order-abi 2
   :backend :datahike
   :source-scope {:source-id "demo.eacl.dev/datahike" :branch nil}
   :snapshot-id
   {:database-id {:store {:backend :s3
                          :bucket "eacl-datahike-demo-example"
                          :region "us-east-1"}}
    :attribute-refs? true
    :basis-t 1000007}
   :schema-stamp 72
   :plan-fingerprint "sealed-plan-fingerprint-example"
   :operation :lookup-resources
   :propagation-direction :forward
   :principal {:type :user :id "alice"}
   :root {:resource-type :document :permission :view}
   :filters {:status :active}
   :result-type :document
   :page-size 50})

(def base-edge
  {:ordinal 151
   :identity {:type :document :id "document-000151"}})

(defn- safe-natural?
  [value]
  (and (integer? value)
       (<= 0 value secure-format/maximum-safe-integer)))

(defn- exact-keys?
  [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- valid-context?
  [context]
  (and
   (map? context)
   (every? #(contains? context %)
           [:semantic-abi :reducer-abi :order-abi :backend
            :source-scope :snapshot-id :schema-stamp :plan-fingerprint
            :operation :propagation-direction :principal :root :filters
            :result-type :page-size])
   (= 8 (:semantic-abi context))
   (= 2 (:reducer-abi context))
   (= 2 (:order-abi context))
   (keyword? (:backend context))
   (map? (:source-scope context))
   (map? (:snapshot-id context))
   (safe-natural? (:schema-stamp context))
   (and (string? (:plan-fingerprint context))
        (not-empty (:plan-fingerprint context)))
   (contains? #{:lookup-resources :lookup-subjects} (:operation context))
   (contains? #{:forward :reverse} (:propagation-direction context))
   (map? (:principal context))
   (map? (:root context))
   (map? (:filters context))
   (keyword? (:result-type context))
   (and (safe-natural? (:page-size context))
        (pos? (:page-size context))
        (<= (:page-size context) maximum-page-size))
   (not (contains? context :navigation-mode))))

(defn- valid-edge?
  [edge]
  (and
   (exact-keys? edge #{:ordinal :identity})
   (safe-natural? (:ordinal edge))
   (pos? (:ordinal edge))
   (exact-keys? (:identity edge) #{:type :id})
   (keyword? (get-in edge [:identity :type]))
   (string? (get-in edge [:identity :id]))
   (not-empty (get-in edge [:identity :id]))))

(defn- contains-private-key?
  [value]
  (let [found (volatile! false)]
    (walk/postwalk
     (fn [item]
       (when (and (map? item)
                  (some private-keys (keys item)))
         (vreset! found true))
       item)
     value)
    @found))

(defn issue-cursor
  ([context edge expires-at]
   (issue-cursor (codec-options) context edge expires-at))
  ([options context edge expires-at]
   (when-not (and (valid-context? context)
                  (valid-edge? edge)
                  (safe-natural? expires-at))
     (throw (ex-info "Invalid edge cursor issuance input."
                     {:context context :edge edge :expires-at expires-at})))
   (let [payload {:format 1
                  :context context
                  :edge edge
                  :expires-at expires-at}
         token (secure-format/encode-authenticated options payload)]
     (when (or (> (count token) maximum-token-size)
               (contains-private-key? payload))
       (throw (ex-info "Edge cursor violates its public size/state contract."
                       {:token-size (count token)})))
     token)))

(defn decode-cursor!
  ([expected-context now token navigation-mode]
   (decode-cursor!
    (codec-options) expected-context now token navigation-mode))
  ([options expected-context now token navigation-mode]
   (when-not (contains? #{:after :before} navigation-mode)
     (throw (ex-info "Unknown Relay navigation mode."
                     {:navigation-mode navigation-mode})))
   (let [{:keys [format context edge expires-at] :as payload}
         (secure-format/decode-authenticated options token)]
     (when-not (and (= 1 format)
                    (valid-context? context)
                    (= expected-context context)
                    (valid-edge? edge)
                    (safe-natural? expires-at)
                    (safe-natural? now)
                    (<= now expires-at)
                    (not (contains-private-key? payload)))
       (throw (ex-info "Authenticated edge cursor is stale or incompatible."
                       {:type :eacl.pagination/stale-cursor})))
     {:context context :edge edge :expires-at expires-at})))

(defn- rejected?
  [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(defn- tamper-character
  [token]
  (let [position (dec (count token))
        old (.charAt token position)
        replacement (if (= old \A) \B \A)]
    (str (subs token 0 position) replacement)))

(defn- signed-payload-token
  [payload]
  (secure-format/encode-authenticated (codec-options) payload))

(defn- checkpoint-key
  [context boundary]
  (secure-format/canonical-digest
   checkpoint-domain
   {:context context :boundary boundary}))

(defn- checkpoint-lookup
  [entries key-fn context boundary]
  (let [key (key-fn context boundary)]
    (some (fn [entry]
            (when (and (= key (:key entry))
                       (= context (:context entry))
                       (= boundary (:boundary entry))
                       (= :reducer-checkpoint (:kind entry)))
              entry))
          entries)))

(defn- mutate-contexts
  [context]
  [(update context :semantic-abi inc)
   (update context :reducer-abi inc)
   (update context :order-abi inc)
   (assoc context :backend :datomic)
   (assoc-in context [:source-scope :source-id] "other-source")
   (update-in context [:snapshot-id :basis-t] inc)
   (update context :schema-stamp inc)
   (assoc context :plan-fingerprint "other-plan")
   (assoc context :operation :lookup-subjects)
   (assoc context :propagation-direction :reverse)
   (assoc-in context [:principal :id] "mallory")
   (assoc-in context [:root :permission] :edit)
   (assoc context :filters {:status :deleted})
   (assoc context :result-type :folder)
   (update context :page-size inc)])

(defn run-bridge!
  []
  (let [now 2000000000
        expires-at (+ now 3600)
        token (issue-cursor base-context base-edge expires-at)
        after (decode-cursor! base-context now token :after)
        before (decode-cursor! base-context now token :before)
        context-rejections
        (mapv (fn [context]
                (rejected? #(decode-cursor! context now token :after)))
              (mutate-contexts base-context))
        tampered? (rejected?
                   #(decode-cursor! base-context now
                                    (tamper-character token) :after))
        expired? (rejected?
                  #(decode-cursor! base-context (inc expires-at)
                                   token :after))
        wrong-domain?
        (rejected?
         #(decode-cursor!
           (assoc (codec-options) :domain "other-domain")
           base-context now token :after))
        oversized-page?
        (rejected?
         #(issue-cursor
           (assoc base-context :page-size (inc maximum-page-size))
           base-edge expires-at))
        zero-ordinal?
        (rejected?
         #(issue-cursor base-context (assoc base-edge :ordinal 0) expires-at))
        unknown-field?
        (let [payload {:format 1
                       :context base-context
                       :edge base-edge
                       :expires-at expires-at
                       :checkpoint "forbidden"}]
          (rejected?
           #(decode-cursor!
             base-context now (signed-payload-token payload) :after)))
        old-options (codec-options :k1 {:k1 key-one})
        old-token (issue-cursor old-options base-context base-edge expires-at)
        rotated-options (codec-options :k2 {:k1 key-one :k2 key-two})
        old-key-accepted?
        (= (:edge after)
           (:edge (decode-cursor! rotated-options base-context now
                                  old-token :after)))
        retired-key-rejected?
        (rejected?
         #(decode-cursor!
           (codec-options :k2 {:k2 key-two})
           base-context now old-token :after))
        boundary {:kind :after :delivered 151}
        correct-entry {:key (checkpoint-key base-context boundary)
                       :context base-context
                       :boundary boundary
                       :kind :reducer-checkpoint
                       :checkpoint {:private true}}
        wrong-entry {:key :forced-collision
                     :context (assoc base-context :plan-fingerprint "wrong")
                     :boundary boundary
                     :kind :reducer-checkpoint
                     :checkpoint {:wrong true}}
        constant-key (fn [& _] :forced-collision)
        collision-misses?
        (nil? (checkpoint-lookup
               [wrong-entry] constant-key base-context boundary))
        collision-finds-only-exact?
        (= (assoc correct-entry :key :forced-collision)
           (checkpoint-lookup
            [(assoc correct-entry :key :forced-collision) wrong-entry]
            constant-key base-context boundary))
        controls
        {:same-token-both-directions? (= after before)
         :all-context-mutations-rejected? (every? true? context-rejections)
         :tamper-rejected? tampered?
         :expiry-rejected? expired?
         :wrong-domain-rejected? wrong-domain?
         :oversized-page-rejected? oversized-page?
         :zero-ordinal-rejected? zero-ordinal?
         :unknown-field-rejected? unknown-field?
         :old-key-accepted-during-rotation? old-key-accepted?
         :retired-key-rejected? retired-key-rejected?
         :checkpoint-collision-misses? collision-misses?
         :checkpoint-collision-finds-only-exact?
         collision-finds-only-exact?
         :no-private-state? (not (contains-private-key? after))
         :bounded-token? (<= (count token) maximum-token-size)}]
    (when-not (every? true? (vals controls))
      (throw (ex-info "Cursor refinement bridge failed."
                      {:controls controls
                       :context-rejections context-rejections
                       :token-size (count token)})))
    {:token-size (count token)
     :context-mutation-count (count context-rejections)
     :control-count (count controls)
     :controls controls}))
