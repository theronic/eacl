(ns eacl.operator.bitmask
  "Bounded 32-bit candidate masks with primitive runtime storage and a
  canonical signed-word portable form."
  (:refer-clojure :exclude [empty?]))

(def bits-per-word 32)
(def maximum-width 256)

(defn word-count [width]
  (when-not (and (integer? width) (<= 0 width maximum-width))
    (throw
     (ex-info "Candidate-mask width is outside the supported range."
              {:type :eacl.operator/invalid-mask
               :eacl/error :eacl.operator/invalid-mask
               :width width :maximum-width maximum-width})))
  (quot (+ width (dec bits-per-word)) bits-per-word))

(defn native
  "Allocates zeroed primitive signed 32-bit words."
  [width]
  {:width width
   :words #?(:clj (int-array (word-count width))
             :cljs (js/Int32Array. (word-count width)))})

(defn- check-index!
  "Mutator guard: an out-of-range write would silently set a padding bit
  and corrupt the canonical portable form that cache entries rely on.
  Reads need no guard — padding bits are provably zero."
  [{:keys [width]} index]
  (when-not (and (integer? index) (<= 0 index) (< index width))
    (throw
     (ex-info "Candidate-mask bit index is outside the mask."
              {:type :eacl.operator/invalid-mask
               :eacl/error :eacl.operator/invalid-mask
               :index index :width width}))))

(defn- word-at [words word-index]
  #?(:clj (aget ^ints words (int word-index))
     :cljs (aget words word-index)))

(defn set-bit!
  [{:keys [words] :as mask} index]
  (check-index! mask index)
  (let [word-index (quot index bits-per-word)
        bit-index (mod index bits-per-word)
        value (bit-or (word-at words word-index)
                      (bit-shift-left 1 bit-index))]
    #?(:clj (aset-int ^ints words (int word-index) (unchecked-int value))
       :cljs (aset words word-index value))
    mask))

(defn clear-bit!
  [{:keys [words] :as mask} index]
  (check-index! mask index)
  (let [word-index (quot index bits-per-word)
        bit-index (mod index bits-per-word)
        value (bit-and (word-at words word-index)
                       (bit-not (bit-shift-left 1 bit-index)))]
    #?(:clj (aset-int ^ints words (int word-index) (unchecked-int value))
       :cljs (aset words word-index value))
    mask))

(defn bit-set?
  [{:keys [words]} index]
  (not (zero? (bit-and (word-at words (quot index bits-per-word))
                       (bit-shift-left 1 (mod index bits-per-word))))))

(defn portable
  "Returns canonical signed 32-bit words. Bits above :width remain zero."
  [{:keys [width words]}]
  {:width width
   :words (mapv #(bit-or 0 (word-at words %))
                (range #?(:clj (alength ^ints words)
                          :cljs (.-length words))))})

(defn from-indexes [width indexes]
  (let [mask (native width)]
    (doseq [index indexes] (set-bit! mask index))
    mask))

(defn indexes [{:keys [width] :as mask}]
  (into [] (filter #(bit-set? mask %)) (range width)))

(defn empty? [{:keys [words]}]
  (let [n #?(:clj (alength ^ints words) :cljs (.-length words))]
    (loop [i 0]
      (cond
        (== i n) true
        (zero? (word-at words i)) (recur (inc i))
        :else false))))
