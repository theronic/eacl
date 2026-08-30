(ns eacl.causal-token
  "Version-4 authenticated backend-native revision tokens."
  (:require [clojure.string :as str]
            [eacl.secure-format :as secure]))

(def token-version 4)
(def token-prefix "eacl_z4_")
(def legacy-token-prefix "eacl_z3_")
(def token-domain "eacl/zed-token/envelope/v4")
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
  []
  (throw
   (ex-info
    "EACL v3 graph-anchor tokens are not accepted; request a v4 native revision token."
    {:type :eacl/zed-token-upgrade-required
     :eacl/error :eacl/zed-token-upgrade-required
     :reason :legacy-graph-token
     :from-version 3
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
          {:maximum-size maximum-scope-characters})
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

(defn validate-source-lifecycle!
  [value]
  (when-not (and (bounded-canonical-value? value)
                 (not= "" value))
    (invalid-token! :invalid-source-lifecycle {}))
  value)

(defn validate-payload!
  [payload]
  (let [{:keys [version backend source-id source-lifecycle branch revision
                exact-locator issued-at expires-at]} payload]
    (when-not (and (= payload-keys (set (keys payload)))
                   (= token-version version)
                   (keyword? backend)
                   (bounded-canonical-value? source-id)
                   (or (nil? branch) (bounded-canonical-value? branch))
                   (bounded-canonical-value? source-lifecycle)
                   (natural-revision? revision)
                   (exact-locator? exact-locator)
                   (integer? issued-at)
                   (integer? expires-at)
                   (not (neg? issued-at))
                   (<= issued-at expires-at))
      (invalid-token! :malformed {})))
  payload)

(defn issue
  "Issues an authenticated v4 backend-native revision token."
  [{:keys [token-ttl-seconds] :as options} payload]
  (let [issued-at (or (:issued-at payload) (now-seconds))
        ttl (or token-ttl-seconds 3600)
        expires-at (or (:expires-at payload) (+ issued-at ttl))
        payload (-> payload
                    (assoc :version token-version
                           :issued-at issued-at
                           :expires-at expires-at)
                    (update :branch #(or % nil))
                    (update :exact-locator #(or % nil)))]
    (validate-payload! payload)
    (secure/encode-authenticated
     (merge options
            {:domain token-domain
             :prefix token-prefix})
     payload)))

(defn token-data
  "Authenticates a v4 token and optionally validates its source lifecycle."
  ([options token]
   (token-data options nil token))
  ([options expected-scope token]
   (when (and (string? token)
              (str/starts-with? token legacy-token-prefix))
     (legacy-token!))
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
