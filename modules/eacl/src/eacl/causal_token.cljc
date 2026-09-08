(ns eacl.causal-token
  "Version-5 authenticated backend-native revision tokens with UUID lifecycles."
  (:require [clojure.string :as str]
            [eacl.secure-format :as secure]
            [eacl.uuid :as uuid]))

(def token-version 5)
(def token-prefix "eacl_z5_")
(def legacy-token-prefix "eacl_z3_")
(def token-domain "eacl/zed-token/envelope/v5")
(def default-token-ttl-seconds 3600)
(def payload-keys
  #{:version :backend :source-id :source-lifecycle :branch :revision
    :exact-locator :issued-at :expires-at})
(def maximum-exact-integer 9007199254740991)
(def maximum-scope-characters 4096)

(defn now-seconds
  []
  (quot (#?(:clj System/currentTimeMillis
            :cljs js/Date.now))
        1000))

(defn- invalid-token!
  [reason data]
  (throw (ex-info "Invalid EACL causal token."
                  (merge {:type :eacl/invalid-zed-token
                          :eacl/error :eacl/invalid-zed-token
                          :reason reason}
                         data))))

(defn- legacy-token!
  [version]
  (throw
   (ex-info
    "This EACL token format is obsolete; request a fresh v5 UUID-lifecycle token."
    {:type :eacl/zed-token-upgrade-required
     :eacl/error :eacl/zed-token-upgrade-required
     :reason (if (= 3 version) :legacy-graph-token :legacy-source-lifecycle)
     :from-version version
     :to-version token-version})))

(defn- bounded-canonical-value?
  [value]
  (and (or (string? value)
           (keyword? value)
           (map? value)
           (vector? value))
       (try
         (secure/encode-canonical
          value
          {:maximum-size maximum-scope-characters :allow-uuids? false})
         true
         (catch #?(:clj Exception :cljs :default) _
           false))))

(defn- natural-revision?
  [value]
  (and (integer? value)
       (not (neg? value))
       (<= value maximum-exact-integer)))

(defn exact-locator?
  "True for the closed portable locator domain shared by basis identities,
  completed-cache provenance, and causal tokens."
  [value]
  (or (nil? value)
      (natural-revision? value)
      (and (string? value)
           (not-empty value)
           (<= (count value) maximum-scope-characters))))

(defn ^:no-doc source-scope?
  "The unchanged portable backend/source/branch identity domain."
  [{:keys [backend source-id branch]}]
  (and (keyword? backend)
       (bounded-canonical-value? source-id)
       (or (nil? branch) (bounded-canonical-value? branch))))

(defn validate-source-lifecycle!
  [value]
  (or (uuid/capture value)
      (let [legacy? (or (string? value) (keyword? value) (map? value) (vector? value))
            error-type (if legacy? :eacl/source-lifecycle-upgrade-required
                           :eacl/invalid-source-lifecycle)]
        (throw (ex-info "EACL source lifecycle requires a native UUID; provision shared configuration before upgrading."
                        {:type error-type :eacl/error error-type
                         :reason :invalid-source-lifecycle :expected :uuid})))))

(defn validate-payload!
  [payload]
  (let [{:keys [version backend source-id source-lifecycle branch revision
                exact-locator issued-at expires-at]} payload]
    (when-not (and (= payload-keys (set (keys payload)))
                   (= token-version version)
                   (source-scope? payload)
                   (uuid/value? source-lifecycle)
                   (natural-revision? revision)
                   (exact-locator? exact-locator)
                   (integer? issued-at)
                   (integer? expires-at)
                   (not (neg? issued-at))
                   (<= issued-at expires-at))
      (invalid-token! :malformed {})))
  payload)

(defn issue
  "Issues an authenticated v5 backend-native revision token."
  [{:keys [token-ttl-seconds] :as options} payload]
  (let [issued-at (or (:issued-at payload) (now-seconds))
        ttl (or token-ttl-seconds 3600)
        expires-at (or (:expires-at payload) (+ issued-at ttl))
        payload (-> payload
                    (assoc :version token-version
                           :issued-at issued-at
                           :expires-at expires-at)
                    (update :source-lifecycle validate-source-lifecycle!)
                    (update :branch #(or % nil))
                    (update :exact-locator #(or % nil)))]
    (validate-payload! payload)
    (secure/encode-authenticated
     (merge options
            {:domain token-domain
             :prefix token-prefix})
     payload)))

(defn token-data
  "Authenticates a v5 token and optionally validates its source lifecycle."
  ([options token]
   (token-data options nil token))
  ([options expected-scope token]
   (when (string? token)
     (cond
       (str/starts-with? token legacy-token-prefix) (legacy-token! 3)
       (str/starts-with? token "eacl_z4_") (legacy-token! 4)))
   (let [payload
         (try
           (secure/decode-authenticated
            (merge options
                   {:domain token-domain
                    :prefix token-prefix
                    :payload-keys payload-keys})
            token)
           (catch #?(:clj Exception :cljs :default) error
             (if (contains? #{:eacl/invalid-zed-token
                              :eacl/zed-token-upgrade-required}
                            (:type (ex-data error)))
               (throw error)
               (invalid-token! (:reason (ex-data error)) {}))))
         payload (validate-payload! payload)
         now (or (:now-seconds options) (now-seconds))]
     (when (> now (:expires-at payload))
       (invalid-token! :expired {:expired-at (:expires-at payload)}))
     (when (and expected-scope
                (not= expected-scope
                      (select-keys payload
                                   [:backend :source-id
                                    :source-lifecycle :branch])))
       (invalid-token! :scope-mismatch
                       {:expected-scope expected-scope
                        :actual-scope
                        (select-keys payload
                                     [:backend :source-id
                                      :source-lifecycle :branch])}))
     payload)))
