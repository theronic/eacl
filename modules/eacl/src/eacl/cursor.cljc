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
  "Serializes an internal cursor map to an opaque string token with TTL."
  [cursor & [{:keys [ttl-seconds] :or {ttl-seconds 300}}]]
  (when cursor
    (let [with-expiry (assoc cursor :t (+ (now-seconds) ttl-seconds))]
      (str "eacl1_" (encode-string (pr-str with-expiry))))))

(defn token->cursor
  "Deserializes an opaque cursor token. Raw cursor maps are accepted for
  backward compatibility."
  [token-or-cursor]
  (cond
    (nil? token-or-cursor) nil
    (map? token-or-cursor) token-or-cursor
    (and (string? token-or-cursor)
         (.startsWith ^String token-or-cursor "eacl1_"))
    (try
      (let [cursor (edn/read-string (decode-string (subs token-or-cursor 6)))
            now    (now-seconds)]
        (when (> (:t cursor now) now)
          (dissoc cursor :t)))
      (catch #?(:clj Exception :cljs :default) _
        nil))
    :else nil))
