(ns eacl.cursor
  (:require [clojure.edn :as edn]))

(defn- now-seconds []
  (quot (#?(:clj System/currentTimeMillis
            :cljs js/Date.now))
        1000))

(defn- encode-string [value]
  #?(:clj  (.encodeToString
            (java.util.Base64/getEncoder)
            (.getBytes value "UTF-8"))
     :cljs (if-let [btoa (.-btoa js/globalThis)]
             (.call btoa js/globalThis value)
             (.toString (.from js/Buffer value "utf8") "base64"))))

(defn- decode-string [value]
  #?(:clj  (String. (.decode (java.util.Base64/getDecoder)
                      (.getBytes value "UTF-8"))
              "UTF-8")
     :cljs (if-let [atob (.-atob js/globalThis)]
             (.call atob js/globalThis value)
             (.toString (.from js/Buffer value "base64") "utf8"))))

(defn cursor->token
  "Serializes an internal cursor map to an opaque string token.
  An expiry timestamp (:t, epoch seconds) is embedded only when the client is
  configured with :cursor-ttl-seconds; by default tokens do not expire -
  slow batch pagination must never silently restart (audit 7)."
  ([cursor] (cursor->token cursor nil))
  ([cursor {:keys [cursor-ttl-seconds]}]
   (when cursor
     (let [cursor' (if cursor-ttl-seconds
                     (assoc cursor :t (+ (now-seconds) cursor-ttl-seconds))
                     cursor)]
       (str "eacl1_" (encode-string (pr-str cursor')))))))

(defn token->cursor
  "Deserializes an opaque cursor token.

  Contract:
  - nil means first page and returns nil;
  - raw cursor maps pass through (backward compatibility);
  - any other non-nil input that fails to decode throws
    ex-info {:type :eacl/invalid-cursor :reason :undecodable};
  - a token carrying an expiry (:t) throws {:reason :expired} when expired and
    the client is configured with :cursor-ttl-seconds. Tokens without :t never
    expire. A bad cursor must fail loudly - decoding to nil silently restarted
    pagination from the first page (audit 7)."
  ([token-or-cursor] (token->cursor token-or-cursor nil))
  ([token-or-cursor {:keys [cursor-ttl-seconds]}]
   (cond
     (nil? token-or-cursor) nil
     (map? token-or-cursor) token-or-cursor

     (and (string? token-or-cursor)
          #?(:clj (.startsWith ^String token-or-cursor "eacl1_")
             :cljs (.startsWith token-or-cursor "eacl1_")))
     (let [cursor (try
                    (edn/read-string (decode-string (subs token-or-cursor 6)))
                    (catch #?(:clj Exception :cljs :default) e
                      (throw (ex-info "Invalid cursor token: cannot be decoded."
                               {:type :eacl/invalid-cursor
                                :reason :undecodable}
                               e))))]
       (when-not (map? cursor)
         (throw (ex-info "Invalid cursor token: does not decode to a cursor map."
                  {:type :eacl/invalid-cursor
                   :reason :undecodable})))
       (if (and cursor-ttl-seconds (:t cursor) (> (now-seconds) (:t cursor)))
         (throw (ex-info "Invalid cursor token: expired."
                  {:type :eacl/invalid-cursor
                   :reason :expired
                   :expired-at (:t cursor)}))
         (dissoc cursor :t)))

     :else
     (throw (ex-info "Invalid cursor token: unrecognized format."
              {:type :eacl/invalid-cursor
               :reason :undecodable
               :token token-or-cursor})))))
