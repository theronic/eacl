(ns eacl.cursor
  "Authenticated portable cursor envelopes.

  Confidentiality is a separate adapter capability. Datomic retains its
  encrypted page-token codec; this portable format provides mandatory
  authenticity for synchronous CLJ/CLJS clients."
  (:require [eacl.secure-format :as secure]))

(def cursor-version 3)
(def cursor-prefix "eacl_c3_")
(def cursor-domain "eacl/cursor/envelope/v3")
(def payload-keys #{:version :cursor :issued-at :expires-at})

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

(defn- codec-identity
  [options]
  (let [{:keys [current-kid keyring]} (format-options options)
        kid (or current-kid :default)
        keyring (or keyring {:default secure/default-root-key})]
    [kid (get keyring kid)]))

(defn- memoizable-cache
  [{:keys [cursor-codec-cache cursor-ttl-seconds]
    :as options}]
  (when (and cursor-codec-cache
             (nil? cursor-ttl-seconds)
             (not= false
                   (:completed-cache-request? options)))
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
  "Authenticates an internal cursor map as an opaque version-3 token."
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
                 (secure/encode-authenticated
                  (merge (format-options options)
                         {:domain cursor-domain
                          :prefix cursor-prefix})
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
                   (secure/decode-authenticated
                    (merge (format-options options)
                           {:domain cursor-domain
                            :prefix cursor-prefix
                            :payload-keys payload-keys})
                    token)
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
