(ns eacl.cursor
  "Authenticated portable cursor envelopes.

  Confidentiality is a separate adapter capability. Datomic retains its
  encrypted page-token codec; this portable format provides mandatory
  authenticity for synchronous CLJ/CLJS clients."
  (:require [clojure.string :as str]
            [eacl.secure-format :as secure]))

(def cursor-version 4)
(def cursor-prefix "eacl_c4_")
(def cursor-domain "eacl/cursor/envelope/v4")
(def payload-keys #{:version :cursor :issued-at :expires-at})

(def ^:dynamic *codec-work*
  "Optional atom populated with deterministic cursor-codec work counters.

  These counters are a non-timing regression boundary: tests can reject an
  additional whole-payload canonicalization, authentication pass, or Base64
  pass without depending on host load or JIT state."
  nil)

(defn- record-work!
  [field amount]
  (when *codec-work*
    (swap! *codec-work* update field (fnil + 0) amount)))

(defrecord CursorCodecCache [state max-entries])

(defn codec-cache
  "Creates a bounded, client-private cache for non-expiring cursor codecs.

  Tokens found here were authenticated when this exact client minted them.
  Unknown tokens still pass through the authenticated decoder."
  ([]
   (codec-cache {}))
  ([{:keys [max-entries]
     :or {max-entries 2048}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw (ex-info "Cursor codec cache :max-entries must be positive."
                     {:type :eacl/invalid-config
                      :max-entries max-entries})))
   (->CursorCodecCache
    (atom {:order []
           :by-token {}
           :by-cursor {}})
    max-entries)))

(defn clear-codec-cache!
  [cache]
  (when cache
    (when-not (instance? CursorCodecCache cache)
      (throw (ex-info "Expected an EACL cursor codec cache."
                      {:type :eacl/invalid-config})))
    (reset! (:state cache)
            {:order []
             :by-token {}
             :by-cursor {}}))
  nil)

(defn- now-seconds
  [options]
  (or (:now-seconds options)
      (quot (#?(:clj System/currentTimeMillis
                :cljs js/Date.now))
            1000)))

(defn- cursor-error!
  [reason data]
  (throw (ex-info "Invalid EACL cursor."
                  (merge {:type :eacl/invalid-cursor
                          :eacl/error :eacl.pagination/invalid-cursor
                          :reason reason}
                         data))))

(defn- format-options
  [options]
  (merge options (:format-options options)))

(defn- compact-signing-input
  [kid-segment payload-segment]
  (str cursor-domain "\n" kid-segment "." payload-segment))

(defn- encode-compact
  [options payload]
  (let [format-options (format-options options)
        {:keys [kid key]}
        (secure/signing-context format-options cursor-domain)
        kid-bytes
        (secure/utf8-bytes
         (secure/encode-canonical
          kid
          (assoc format-options :maximum-size 1024)))
        payload-bytes
        (secure/utf8-bytes
         (secure/encode-canonical payload format-options))
        kid-segment (secure/b64url-encode kid-bytes)
        payload-segment (secure/b64url-encode payload-bytes)
        signing-input
        (compact-signing-input kid-segment payload-segment)
        tag
        (secure/hmac-sha-256 key signing-input)]
    (record-work! :encode-calls 1)
    (record-work! :payload-canonical-passes 1)
    (record-work! :payload-input-bytes (count payload-bytes))
    (record-work! :authentication-passes 1)
    (record-work! :authentication-input-bytes
                  (count (secure/utf8-bytes signing-input)))
    (record-work! :base64-encode-passes 3)
    (str cursor-prefix
         kid-segment "."
         payload-segment "."
         (secure/b64url-encode tag))))

(defn- decode-compact
  [options token]
  (let [format-options (format-options options)
        maximum-size
        (or (:maximum-size format-options)
            secure/default-maximum-size)]
    (when-not (and (string? token)
                   (<= (count token) maximum-size)
                   (str/starts-with? token cursor-prefix))
      (throw (ex-info "Invalid compact cursor."
                      {:type :eacl.format/invalid
                       :reason :malformed-token})))
    (let [segments
          (str/split
           (subs token (count cursor-prefix))
           #"\."
           -1)]
      (when-not (and (= 3 (count segments))
                     (every? not-empty segments))
        (throw (ex-info "Invalid compact cursor."
                        {:type :eacl.format/invalid
                         :reason :malformed-token})))
      (let [[kid-segment payload-segment tag-segment] segments
            _ (when (> (count kid-segment) 1368)
                (throw (ex-info "Invalid compact cursor key id."
                                {:type :eacl.format/invalid
                                 :reason :malformed-token})))
            kid
            (secure/decode-canonical
             (secure/bytes->utf8
              (secure/b64url-decode kid-segment))
             {:maximum-size 1024})
            root-key
            (get
             (or (:keyring format-options)
                 {:default secure/default-root-key})
             kid)]
        (when-not root-key
          (throw (ex-info "Compact cursor authentication failed."
                          {:type :eacl.format/invalid
                           :reason :authentication-failed})))
        (let [key (secure/derive-key root-key cursor-domain)
              signing-input
              (compact-signing-input kid-segment payload-segment)
              expected
              (secure/hmac-sha-256 key signing-input)
              supplied
              (secure/b64url-decode tag-segment)]
          (record-work! :decode-calls 1)
          (record-work! :authentication-passes 1)
          (record-work! :authentication-input-bytes
                        (count (secure/utf8-bytes signing-input)))
          (record-work! :base64-decode-passes 3)
          (when-not (secure/secure-equal? expected supplied)
            (throw
             (ex-info "Compact cursor authentication failed."
                      {:type :eacl.format/invalid
                       :reason :authentication-failed})))
          (let [payload-bytes
                (secure/b64url-decode payload-segment)
                payload
                (secure/decode-canonical
                 (secure/bytes->utf8 payload-bytes)
                 (cond-> format-options
                   payload-keys
                   (assoc :allowed-keys payload-keys)))]
            (record-work! :payload-canonical-passes 1)
            (record-work! :payload-input-bytes (count payload-bytes))
            payload))))))

(defn- codec-identity
  [options]
  (let [{:keys [current-kid keyring]} (format-options options)
        kid (or current-kid :default)
        keyring (or keyring {:default secure/default-root-key})]
    [kid (get keyring kid)]))

(defn- memoizable-cache
  [{:keys [cursor-codec-cache cursor-ttl-seconds]}]
  (when (and cursor-codec-cache
             (nil? cursor-ttl-seconds))
    (when-not (instance? CursorCodecCache cursor-codec-cache)
      (throw (ex-info "Expected an EACL cursor codec cache."
                      {:type :eacl/invalid-config})))
    cursor-codec-cache))

(defn- cached-token
  [cache identity cursor]
  (get-in @(:state cache) [:by-cursor [identity cursor]]))

(defn- cached-cursor
  [cache identity token]
  (let [entry (get-in @(:state cache) [:by-token token])]
    (when (= identity (:identity entry))
      (:cursor entry))))

(defn- remember-token!
  [cache identity cursor token]
  (swap!
   (:state cache)
   (fn [{:keys [order by-token by-cursor] :as state}]
     (if (contains? by-token token)
       state
       (let [cursor-key [identity cursor]
             order' (conj order token)
             by-token'
             (assoc by-token token
                    {:identity identity
                     :cursor cursor})
             by-cursor' (assoc by-cursor cursor-key token)
             overflow (- (count order') (:max-entries cache))
             evicted (when (pos? overflow)
                       (take overflow order'))]
         (if-not (seq evicted)
           {:order order'
            :by-token by-token'
            :by-cursor by-cursor'}
           (reduce
            (fn [current evicted-token]
              (let [{evicted-identity :identity
                     evicted-cursor :cursor}
                    (get-in current [:by-token evicted-token])]
                (-> current
                    (update :by-token dissoc evicted-token)
                    (update :by-cursor
                            dissoc
                            [evicted-identity evicted-cursor]))))
            {:order (vec (drop overflow order'))
             :by-token by-token'
             :by-cursor by-cursor'}
            evicted))))))
  token)

(defn cursor->token
  "Authenticates an internal cursor map as an opaque compact version-4 token."
  ([cursor]
   (cursor->token cursor nil))
  ([cursor {:keys [cursor-ttl-seconds] :as options}]
   (when cursor
     (when-not (map? cursor)
       (cursor-error! :malformed {}))
     (let [cache (memoizable-cache options)
           identity (when cache (codec-identity options))]
       (or (when cache
             (cached-token cache identity cursor))
           (let [issued-at (now-seconds options)
                 expires-at (when cursor-ttl-seconds
                              (+ issued-at cursor-ttl-seconds))
                 token
                 (encode-compact
                  options
                  {:version cursor-version
                   :cursor cursor
                   :issued-at issued-at
                   :expires-at expires-at})]
             (if cache
               (remember-token!
                cache identity cursor token)
               token)))))))

(defn token->cursor
  "Authenticates and decodes a cursor. Legacy maps and `eacl1_` tokens fail."
  ([token]
   (token->cursor token nil))
  ([token options]
   (if (nil? token)
     nil
     (let [cache (memoizable-cache options)
           identity (when cache (codec-identity options))]
       (or (when cache
             (cached-cursor cache identity token))
           (let [payload
                 (try
                   (decode-compact options token)
                   (catch #?(:clj Exception :cljs :default) error
                     (cursor-error!
                      (or (:reason (ex-data error)) :undecodable)
                      {})))
                 {:keys [version cursor expires-at]} payload]
             (when-not (and (= cursor-version version)
                            (map? cursor)
                            (integer? (:issued-at payload))
                            (or (nil? expires-at)
                                (integer? expires-at)))
               (cursor-error! :undecodable {}))
             (when (and expires-at
                        (>= (now-seconds options) expires-at))
               (cursor-error!
                :expired
                {:expired-at expires-at
                 :type :eacl.pagination/expired-cursor
                 :eacl/error
                 :eacl.pagination/expired-cursor}))
             cursor))))))
