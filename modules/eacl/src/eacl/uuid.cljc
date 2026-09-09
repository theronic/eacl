(ns eacl.uuid
  "Canonical UUID values at portable identity boundaries.

  CLJS UUIDs wrap mutable host fields. Capture copies caller values, initializes
  their native hash and freezes them. Weak ownership branding avoids retaining
  inputs or repeating capture for the immutable values EACL already owns.
  Host runtime and prototype integrity are trusted, as for Clojure collections.")

(def ^:private canonical-pattern
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

#?(:cljs (defonce ^:private owned-values (js/WeakSet.)))

(defn canonical-text? [text]
  (and (string? text) (= 36 (count text))
       (boolean (re-matches canonical-pattern text))))

(declare owned?)

(defn value?
  "True only for a concrete UUID with a complete canonical 128-bit value."
  [value]
  #?(:clj (uuid? value)
     ;; UUIDs are rare leaves in large portable keys. Reject other concrete
     ;; types before probing the ownership set on every keyword/number/map.
     :cljs (and (instance? UUID value)
                (or (owned? value)
                    (and (identical? (js/Object.getPrototypeOf value) (.-prototype UUID))
                         (canonical-text? (.-uuid value)))))))

(defn owned?
  "True for immutable native UUID values safe to share in captured identities."
  [value]
  #?(:clj (uuid? value)
     :cljs (and (some? value) (.has owned-values value))))

(defn capture
  "Returns an owned native UUID, or nil for an invalid representation."
  [value]
  (if (owned? value)
    value
    #?(:clj nil
       :cljs
       (when (and (instance? UUID value)
                  (identical? (js/Object.getPrototypeOf value) (.-prototype UUID)))
         ;; Read caller text once. Do not call caller protocol methods or trust
         ;; its cached hash. Even an accessor cannot change this held string.
         (let [text (.-uuid value)]
           (when (canonical-text? text)
             (let [copy (UUID. text nil)]
               (hash copy)
               (js/Object.freeze copy)
               (.add owned-values copy)
               copy)))))))

(defn parse-canonical
  "Explicit strict text parsing for the allowlisted canonical EDN reader."
  [text]
  (when (canonical-text? text)
    (capture #?(:clj (java.util.UUID/fromString text)
                :cljs (UUID. text nil)))))

(defn text [value]
  #?(:clj (.toString ^java.util.UUID value)
     :cljs (.-uuid value)))

(def initial
  "Reserved shared initial incarnation; complete source scope remains required."
  (parse-canonical "00000000-0000-0000-0000-000000000000"))

(defn fresh [] (capture (random-uuid)))
