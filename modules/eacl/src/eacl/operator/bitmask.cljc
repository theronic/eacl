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

(defn- check-index! [{:keys [width]} index]
  (when-not (and (integer? index) (<= 0 index) (< index width))
    (throw
     (ex-info "Candidate-mask bit index is outside the mask."
              {:type :eacl.operator/invalid-mask
               :eacl/error :eacl.operator/invalid-mask
               :index index :width width}))))

(defn set-bit!
  [{:keys [words] :as mask} index]
  (check-index! mask index)
  (let [word-index (quot index bits-per-word)
        bit-index (mod index bits-per-word)
        value (bit-or (aget words word-index)
                      (bit-shift-left 1 bit-index))]
    #?(:clj (aset-int ^ints words (int word-index) (unchecked-int value))
       :cljs (aset words word-index value))
    mask))

(defn clear-bit!
  [{:keys [words] :as mask} index]
  (check-index! mask index)
  (let [word-index (quot index bits-per-word)
        bit-index (mod index bits-per-word)
        value (bit-and (aget words word-index)
                       (bit-not (bit-shift-left 1 bit-index)))]
    #?(:clj (aset-int ^ints words (int word-index) (unchecked-int value))
       :cljs (aset words word-index value))
    mask))

(defn bit-set?
  [{:keys [words] :as mask} index]
  (check-index! mask index)
  (not (zero? (bit-and (aget words (quot index bits-per-word))
                       (bit-shift-left 1 (mod index bits-per-word))))))

(defn portable
  "Returns canonical signed 32-bit words. Bits above :width remain zero."
  [{:keys [width words]}]
  {:width width
   :words (mapv #(bit-or 0 (aget words %))
                (range (word-count width)))})

(defn from-indexes [width indexes]
  (let [mask (native width)]
    (doseq [index indexes] (set-bit! mask index))
    mask))

(defn indexes [{:keys [width] :as mask}]
  (into [] (filter #(bit-set? mask %)) (range width)))

(defn empty? [mask]
  (not-any? #(not (zero? %)) (:words (portable mask))))
