(ns eacl.datascript.db
  "Guarded native DataScript index access for endpoint-pair relationship
  values. DataScript compares vectors by length before components, so every
  seek and reverse-seek starts from a complete four-element value."
  (:require [datascript.core :as ds]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(def min-eid 0)

(def max-eid
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_SAFE_INTEGER))

(defn entid
  [db eid-or-ref]
  (ds/entid db eid-or-ref))

(defn entity-exists?
  [db eid]
  (boolean (seq (ds/datoms db :eavt eid))))

(defn avet-datoms
  ([db attr]
   (ds/datoms db :avet attr))
  ([db attr value]
   (ds/datoms db :avet attr value)))

(defn eavt-datoms
  ([db entity attr]
   (ds/datoms db :eavt entity attr))
  ([db entity attr value]
   (ds/datoms db :eavt entity attr value)))

(defn- matching-eavt-prefix?
  [entity attr prefix {:keys [e a] :as datom}]
  (and (= entity e)
       (= attr a)
       (endpoint-pair/value-prefix? (:v datom) prefix)))

(defn- matching-avet-prefix?
  [attr prefix {:keys [a] :as datom}]
  (and (= attr a)
       (endpoint-pair/value-prefix? (:v datom) prefix)))

(defn eavt-endpoint-prefix
  "Endpoint datoms for an exact three-component value prefix.

  `cursor-eid` is an inclusive lower/upper seek bound; portable cursor logic
  decides whether to drop the boundary row. Direction is `:asc` or `:desc`."
  ([db entity attr prefix]
   (eavt-endpoint-prefix db entity attr prefix nil :asc))
  ([db entity attr prefix cursor-eid direction]
   (if-not (and (nat-int? entity)
                (endpoint-pair/valid-prefix? prefix)
                (#{:asc :desc} direction))
     []
     (let [tail  (or cursor-eid
                     (if (= :desc direction) max-eid min-eid))
           bound (conj prefix tail)
           scan  (if (= :desc direction)
                   (ds/rseek-datoms db :eavt entity attr bound)
                   (ds/seek-datoms db :eavt entity attr bound))
           first-datom (first scan)]
       ;; Most recursive probes are empty. Reject a non-matching seek head
       ;; before constructing the predicate closure and lazy take-while chain;
       ;; a matching head retains the same monotone prefix termination.
       (if (and first-datom
                (matching-eavt-prefix? entity attr prefix first-datom))
         (take-while #(matching-eavt-prefix? entity attr prefix %) scan)
         [])))))

(defn avet-endpoint-prefix
  "Endpoint datoms across entities for an exact three-component value prefix,
  using a complete four-component AVET seek bound."
  ([db attr prefix]
   (avet-endpoint-prefix db attr prefix nil :asc))
  ([db attr prefix cursor-eid direction]
   (if-not (and (endpoint-pair/valid-prefix? prefix)
                (#{:asc :desc} direction))
     []
     (let [tail  (or cursor-eid
                     (if (= :desc direction) max-eid min-eid))
           bound (conj prefix tail)
           scan  (if (= :desc direction)
                   (ds/rseek-datoms db :avet attr bound)
                   (ds/seek-datoms db :avet attr bound))
           first-datom (first scan)]
       (if (and first-datom
                (matching-avet-prefix? attr prefix first-datom))
         (take-while #(matching-avet-prefix? attr prefix %) scan)
         [])))))
