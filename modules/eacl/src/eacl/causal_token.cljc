(ns eacl.causal-token
  "Version-3 authenticated causal snapshot tokens."
  (:require [eacl.secure-format :as secure]))

(def token-version 3)
(def token-prefix "eacl_z3_")
(def token-domain "eacl/zed-token/envelope/v3")
(def payload-keys
  #{:version :backend :source-id :branch :graph-anchor :order-hint
    :exact-locator :issued-at :expires-at})

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

(defn- valid-scope-value?
  [value]
  (or (string? value)
      (keyword? value)
      (map? value)
      (vector? value)))

(defn validate-payload!
  [payload]
  (let [{:keys [version backend source-id graph-anchor order-hint
                issued-at expires-at]} payload]
    (when-not (and (= token-version version)
                   (keyword? backend)
                   (valid-scope-value? source-id)
                   (string? graph-anchor)
                   (not-empty graph-anchor)
                   (or (nil? order-hint)
                       (and (integer? order-hint)
                            (not (neg? order-hint))))
                   (integer? issued-at)
                   (integer? expires-at)
                   (<= issued-at expires-at))
      (invalid-token! :malformed {})))
  payload)

(defn issue
  "Issues a v3 token. `payload` supplies backend/source/anchor/locator fields."
  [{:keys [token-ttl-seconds] :as options} payload]
  (let [issued-at (or (:issued-at payload) (now-seconds))
        ttl (or token-ttl-seconds 3600)
        expires-at (or (:expires-at payload) (+ issued-at ttl))
        payload (-> payload
                    (assoc :version token-version
                           :issued-at issued-at
                           :expires-at expires-at)
                    (update :branch #(or % nil))
                    (update :order-hint #(or % nil))
                    (update :exact-locator #(or % nil)))]
    (validate-payload! payload)
    (secure/encode-authenticated
     (merge options
            {:domain token-domain
             :prefix token-prefix})
     payload)))

(defn token-data
  "Authenticates a v3 token and optionally validates its expected source scope."
  ([options token]
   (token-data options nil token))
  ([options expected-scope token]
   (let [payload
         (try
           (secure/decode-authenticated
            (merge options
                   {:domain token-domain
                    :prefix token-prefix
                    :payload-keys payload-keys})
            token)
           (catch #?(:clj Exception :cljs :default) error
             (if (= :eacl/invalid-zed-token (:type (ex-data error)))
               (throw error)
               (invalid-token! (:reason (ex-data error)) {}))))
         payload (validate-payload! payload)
         now (or (:now-seconds options) (now-seconds))]
     (when (> now (:expires-at payload))
       (invalid-token! :expired {:expired-at (:expires-at payload)}))
     (when (and expected-scope
                (not= expected-scope
                      (select-keys payload [:backend :source-id :branch])))
       (invalid-token! :scope-mismatch
                       {:expected-scope expected-scope
                        :actual-scope
                        (select-keys payload
                                     [:backend :source-id :branch])}))
     payload)))
