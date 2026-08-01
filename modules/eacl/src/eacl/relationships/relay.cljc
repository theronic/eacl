(ns eacl.relationships.relay
  "Backend-neutral Relay windowing for already-filtered relationship values."
  (:require [eacl.cursor :as cursor]))

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:private page-keys #{:first :last :after :before :consistency})

(defn- page-error!
  [message data]
  (throw (ex-info message
                  (merge {:eacl/error :eacl.pagination/invalid-cursor}
                         data))))

(defn- scope
  [operation filters]
  [operation (apply dissoc filters page-keys)])

(defn- page-request
  [filters]
  (let [first? (contains? filters :first)
        last? (contains? filters :last)
        after? (contains? filters :after)
        before? (contains? filters :before)]
    (when (or (contains? filters :limit)
              (contains? filters :cursor))
      (page-error! "Relationship reads use :first/:after or :last/:before."
                   {:type :eacl.pagination/legacy-pagination}))
    (when (or (and first? last?)
              (and after? before?)
              (and after? (not first?))
              (and before? (not last?)))
      (page-error! "Invalid Relay relationship pagination arguments."
                   (select-keys filters [:first :last :after :before])))
    (let [direction (if last? :desc :asc)
          size (or (:first filters) (:last filters) default-page-size)
          token (if (= :asc direction)
                  (:after filters)
                  (:before filters))]
      (when-not (and (integer? size)
                     (pos? size)
                     (<= size max-page-size))
        (page-error! "Relationship page size is out of range."
                     {:size size :max max-page-size}))
      {:direction direction :size size :token token})))

(defn- decode-bound
  [opts operation filters snapshot-id token]
  (when token
    (let [value
          (try
            (cursor/token->cursor token opts)
            (catch #?(:clj Exception :cljs :default) error
              (page-error! "Invalid relationship cursor."
                           {:type :eacl.pagination/invalid-cursor
                            :reason (:reason (ex-data error))})))]
      (when-not (and (= 8 (:v value))
                     (= :relationships (:kind value))
                     (integer? (:offset value))
                     (not (neg? (:offset value))))
        (page-error! "Invalid relationship cursor envelope."
                     {:reason :invalid-envelope}))
      (when-not (= (scope operation filters) (:scope value))
        (page-error! "Relationship cursor belongs to a different query."
                     {:reason :query-mismatch}))
      (when-not (= snapshot-id (:snapshot-id value))
        (page-error! "Relationship cursor snapshot is no longer current."
                     {:type :eacl.pagination/stale-cursor
                      :reason :snapshot-changed}))
      (:offset value))))

(defn- encode-bound
  [opts operation filters snapshot-id offset]
  (cursor/cursor->token
   {:v 8
    :kind :relationships
    :scope (scope operation filters)
    :snapshot-id snapshot-id
    :offset offset}
   opts))

(defn paginate
  "Applies a Relay window to a canonical vector of public relationships."
  [opts operation filters snapshot-id items]
  (let [items (vec items)
        n (count items)
        {:keys [direction size token]} (page-request filters)
        bound (decode-bound opts operation filters snapshot-id token)
        [start end]
        (case direction
          :asc (let [start (if bound (inc bound) 0)]
                 [start (min n (+ start size))])
          :desc (let [end (if bound (min bound n) n)]
                  [(max 0 (- end size)) end]))
        page-items (if (< start end)
                     (subvec items start end)
                     [])
        any? (boolean (seq page-items))
        start-offset (when any? start)
        end-offset (when any? (dec end))]
    {:data page-items
     :page-info
     {:start-cursor
      (when start-offset
        (encode-bound opts operation filters snapshot-id start-offset))
      :end-cursor
      (when end-offset
        (encode-bound opts operation filters snapshot-id end-offset))
      :has-next-page? (boolean (and any? (< end n)))
      :has-previous-page? (boolean (and any? (pos? start)))}}))
