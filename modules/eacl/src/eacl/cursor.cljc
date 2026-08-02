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

(defn cursor->token
  "Authenticates an internal cursor map as an opaque version-3 token."
  ([cursor]
   (cursor->token cursor nil))
  ([cursor {:keys [cursor-ttl-seconds] :as options}]
   (when cursor
     (when-not (map? cursor)
       (cursor-error! :malformed {}))
     (let [issued-at (now-seconds options)
           expires-at (when cursor-ttl-seconds
                        (+ issued-at cursor-ttl-seconds))]
       (secure/encode-authenticated
        (merge (format-options options)
               {:domain cursor-domain
                :prefix cursor-prefix})
        {:version cursor-version
         :cursor cursor
         :issued-at issued-at
         :expires-at expires-at})))))

(defn token->cursor
  "Authenticates and decodes a cursor. Legacy maps and `eacl1_` tokens fail."
  ([token]
   (token->cursor token nil))
  ([token options]
   (if (nil? token)
     nil
     (let [payload
           (try
             (secure/decode-authenticated
              (merge (format-options options)
                     {:domain cursor-domain
                      :prefix cursor-prefix
                      :payload-keys payload-keys})
              token)
             (catch #?(:clj Exception :cljs :default) error
               (cursor-error! (or (:reason (ex-data error)) :undecodable)
                              {})))
           {:keys [version cursor expires-at]} payload]
       (when-not (and (= cursor-version version)
                      (map? cursor)
                      (integer? (:issued-at payload))
                      (or (nil? expires-at) (integer? expires-at)))
         (cursor-error! :undecodable {}))
       (when (and expires-at (>= (now-seconds options) expires-at))
         (cursor-error! :expired {:expired-at expires-at
                                  :type :eacl.pagination/expired-cursor
                                  :eacl/error
                                  :eacl.pagination/expired-cursor}))
       cursor))))
