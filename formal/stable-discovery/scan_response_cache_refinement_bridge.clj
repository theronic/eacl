(ns eacl.exploration.scan-response-cache-refinement-bridge
  "Source-shaped refinement bridge for the exact scan-response cache.

  `ScanResponseCache.dfy` proves, over one abstract scan sequence, that a
  served reply equals the adapter's chunk for the same offset and limit and
  that contiguous extension keeps a prefix. This bridge runs the production
  functions `eacl.engine.scan-cache/serve` and `extend-entry` against a
  direct transcription of the model over randomized sequences, bounds, and
  limits in both scan directions, and keeps a small set of model-level
  mutants that the same checks must reject. It is exploration-only source: it
  is executed by the fast verifier, never shipped."
  (:require [eacl.engine.scan-cache :as scan-cache])
  (:import [java.util Random]))

;; ---------------------------------------------------------------------------
;; The model, transcribed
;; ---------------------------------------------------------------------------

(defn- model-chunk
  "`Chunk(values, offset, limit)` from ChunkedScan.dfy."
  [values offset limit]
  (subvec values offset (min (+ offset limit) (count values))))

(defn- model-serve
  "`Serve(prefix, exhausted, offset, limit)`: Some(chunk) or nil."
  [prefix exhausted? offset limit]
  (let [available (- (count prefix) offset)]
    (cond
      (>= available limit) (subvec prefix offset (+ offset limit))
      exhausted? (subvec prefix offset)
      :else nil)))

(defn- model-extend
  "`Extend(values, prefix, offset, limit)` with the exhausted flag the model
  derives from a short chunk."
  [values prefix offset limit]
  (let [reply (model-chunk values offset limit)]
    {:prefix (into (subvec prefix 0 offset) reply)
     :exhausted? (< (count reply) limit)}))

(defn- offset-beyond
  "The model's offset for an exclusive bound: the index of the first value
  strictly beyond it in the scan direction (a linear scan, independent of
  the production binary search)."
  [values bound direction]
  (if (nil? bound)
    0
    (count (take-while (fn [value]
                         (if (= :desc direction)
                           (>= (compare value bound) 0)
                           (<= (compare value bound) 0)))
                       values))))

;; ---------------------------------------------------------------------------
;; Randomized cases
;; ---------------------------------------------------------------------------

(defn- random-sequence
  [^Random rng direction]
  (let [n (.nextInt rng 12)
        ascending (vec (sort (take n (distinct (repeatedly (* 2 (inc n))
                                                            #(.nextInt rng 40))))))]
    (if (= :desc direction) (vec (rseq ascending)) ascending)))

(defn- random-bound
  [^Random rng values]
  (case (.nextInt rng 4)
    0 nil
    1 (when (seq values) (nth values (.nextInt rng (count values))))
    (.nextInt rng 42)))

(defn- prefix-of
  [values k]
  {:prefix (subvec values 0 k) :exhausted? (= k (count values))})

(defn- serve-case!
  [^Random rng direction]
  (let [values (random-sequence rng direction)
        k (.nextInt rng (inc (count values)))
        entry (prefix-of values k)
        bound (random-bound rng values)
        limit (inc (.nextInt rng 6))
        offset (offset-beyond (:prefix entry) bound direction)
        expected (model-serve (:prefix entry) (:exhausted? entry) offset limit)
        actual (scan-cache/serve entry bound limit direction)]
    (when-not (= expected actual)
      (throw (ex-info "production serve diverges from the model"
                      {:values values :entry entry :bound bound :limit limit
                       :direction direction :expected expected :actual actual})))
    ;; The proved property: a served reply is the adapter's chunk beyond the
    ;; bound over the complete sequence.
    (when actual
      (let [true-offset (offset-beyond values bound direction)]
        (when-not (= actual (model-chunk values true-offset limit))
          (throw (ex-info "served reply is not the adapter's chunk"
                          {:values values :entry entry :bound bound
                           :limit limit :direction direction :reply actual})))))
    (some? actual)))

(defn- extend-case!
  [^Random rng direction]
  (let [values (random-sequence rng direction)
        k (.nextInt rng (inc (count values)))
        entry (when (pos? (.nextInt rng 4)) (prefix-of values k))
        bound (random-bound rng values)
        limit (inc (.nextInt rng 6))
        max-prefix 64
        true-offset (offset-beyond values bound direction)
        reply (model-chunk values true-offset limit)
        actual (scan-cache/extend-entry entry bound reply limit direction max-prefix)
        prefix-offset (when entry (offset-beyond (:prefix entry) bound direction))
        contiguous? (cond
                      (nil? bound) true
                      (nil? entry) false
                      :else (or (< prefix-offset (count (:prefix entry)))
                                (and (pos? (count (:prefix entry)))
                                     (zero? (compare bound (peek (:prefix entry)))))))]
    (cond
      (nil? actual)
      (when contiguous?
        ;; Only the covered case may return nil here: a reply the prefix
        ;; already contains yields the same entry, never nil.
        (when (or (nil? entry)
                  (< (- (count (:prefix entry)) prefix-offset) (count reply)))
          (throw (ex-info "production dropped a contiguous extension"
                          {:values values :entry entry :bound bound
                           :limit limit :direction direction :reply reply}))))

      (identical? actual entry)
      (when-not (and entry (>= (- (count (:prefix entry)) prefix-offset)
                              (count reply)))
        (throw (ex-info "production returned the resident entry for a longer reply"
                        {:entry entry :bound bound :reply reply})))

      :else
      (let [offset (if entry prefix-offset 0)
            expected (model-extend values (or (:prefix entry) []) offset limit)]
        (when-not contiguous?
          (throw (ex-info "production retained a fragment"
                          {:values values :entry entry :bound bound :reply reply
                           :actual actual})))
        (when-not (= expected actual)
          (throw (ex-info "production extension diverges from the model"
                          {:values values :entry entry :bound bound :limit limit
                           :direction direction :expected expected :actual actual})))
        ;; The proved property: the extension is a prefix of the sequence.
        (when-not (= (:prefix actual) (subvec values 0 (count (:prefix actual))))
          (throw (ex-info "extension is not a prefix of the scan"
                          {:values values :actual actual})))))
    (some? actual)))

(defn- qualify-random-cases!
  [seed rounds]
  (let [rng (Random. (long seed))]
    (reduce
     (fn [counts _]
       (let [direction (if (zero? (.nextInt rng 2)) :asc :desc)]
         (-> counts
             (update :serve inc)
             (update :served (fnil + 0) (if (serve-case! rng direction) 1 0))
             (update :extend inc)
             (update :extended (fnil + 0) (if (extend-case! rng direction) 1 0)))))
     {:serve 0 :served 0 :extend 0 :extended 0}
     (range rounds))))

;; ---------------------------------------------------------------------------
;; Model-level mutants the checks must reject
;; ---------------------------------------------------------------------------

(defn- killed-controls
  []
  (let [values [1 3 5 7 9]
        entry {:prefix [1 3 5] :exhausted? false}]
    {:short-prefix-serve-killed?
     (let [mutant (fn [prefix _exhausted? offset _limit] (subvec prefix offset))]
       (not= (mutant (:prefix entry) false 2 3)
             (model-chunk values 2 3)))
     :bound-included-killed?
     (let [offset (count (take-while #(< % 3) (:prefix entry)))]
       (not= (model-serve (:prefix entry) false offset 2)
             (model-chunk values (offset-beyond values 3 :asc) 2)))
     :fragment-deposit-killed?
     (let [fragment (model-chunk values 3 2)]
       (not= fragment (subvec values 0 (count fragment))))
     :widened-limit-killed?
     (not= (model-chunk values 0 3) (model-chunk values 0 4))}))

(defn run-bridge!
  []
  (let [counts (qualify-random-cases! 20260902 4000)
        controls (killed-controls)]
    (assert (every? true? (vals controls)))
    (assoc counts :controls controls :control-count (count controls))))
