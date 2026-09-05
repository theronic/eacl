(ns eacl.bench.security-keyring
  "Explicit nREPL performance certification; never runs in the ordinary suite."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.cache :as cache]
            [eacl.cache.standard-lru :as lru]
            [eacl.cache-test]
            [eacl.security.protocols :as protocols]))

(defn ensure! [condition reason]
  (when-not condition (throw (ex-info "Security keyring certification failed." {:reason reason}))))
(defn keys-for [n]
  (into {} (map (fn [i] [(str "epoch-" i) (vec (repeat 32 (inc i)))]) (range n))))
(defn ring [keys] (eacl/security-keyring {:keys keys :active-kid "epoch-0"}))
(defn elapsed-ns [f]
  (let [start (System/nanoTime)] (f) (- (System/nanoTime) start)))
(defn median [samples] (nth (vec (sort samples)) (quot (count samples) 2)))

(defn counted [controller reads lookups]
  (reify protocols/KeyringSource
    (-snapshot [_]
      (swap! reads inc)
      (let [snapshot (protocols/-snapshot controller)
            keys (:keys snapshot)
            lookup (fn [kid absent] (swap! lookups inc) (get keys kid absent))]
        (assoc snapshot :keys
               (reify clojure.lang.ILookup
                 (valAt [_ kid] (lookup kid nil))
                 (valAt [_ kid absent] (lookup kid absent))))))
    (-derive-key [_ snapshot kid root domain version]
      (protocols/-derive-key controller snapshot kid root domain version))))

(defn direct-work [n]
  (let [controller (ring (keys-for n)) reads (atom 0) lookups (atom 0)
        opts {:keyring-controller (counted controller reads lookups)}
        token (cursor/cursor->token {:edge 7} opts)
        encode {:state-reads @reads :named-lookups @lookups}]
    (reset! reads 0) (reset! lookups 0)
    (ensure! (= {:edge 7} (cursor/token->cursor token opts)) :roundtrip)
    (let [decode {:state-reads @reads :named-lookups @lookups}]
      (ensure! (= {:state-reads 1 :named-lookups 1} encode decode) :constant-direct-work)
      {:ring-size n :mint encode :decode decode})))

(defn steady-case [n mode]
  (let [keys (keys-for n)
        opts (merge {:cursor-construction-cache (cursor/codec-cache {:max-entries 64})}
                    (if (= :static mode) {:keyring keys :current-kid "epoch-0"}
                        {:keyring-controller (ring keys)}))
        token (cursor/cursor->token {:edge 7} opts)
        mint #(cursor/cursor->token {:edge 7} opts)
        decode #(cursor/token->cursor token opts)
        sample (fn [f] (/ (double (elapsed-ns #(dotimes [_ 1000] (f)))) 1000.0))]
    (dotimes [_ 2000] (mint) (decode))
    (into {:ring-size n :mode mode}
          (for [[op f] [[:mint mint] [:decode decode]]]
            (let [samples (vec (repeatedly 7 #(sample f)))]
              [op {:median-ns-per-operation (median samples) :samples-ns-per-operation samples}])))))

(defn populated-case [n]
  (let [controller (ring (keys-for 2)) opts {:keyring-controller controller}
        codec (cursor/codec-cache {:max-entries (+ 4 n)})
        cursor-opts (assoc opts :cursor-codec-cache codec)
        bounds {:max-entries (+ 4 n)}
        source (cache/basis-cache {:max-entries (* 4 (+ 4 n))})
        target (cache/basis-cache {:max-entries (* 4 (+ 4 n))})
        semantic (ns-resolve 'eacl.cache-test 'semantic-key)
        exact (ns-resolve 'eacl.cache-test 'exact-operation!)
        read! #(exact target 1 (semantic %) :can? (constantly false))]
    (dotimes [i n]
      (cursor/cursor->token {:edge i} cursor-opts)
      (exact source 1 (semantic i) :can? (constantly true)))
    (let [archive (cache/export-authenticated-basis-snapshot source bounds opts)
          restored (cache/restore-authenticated-basis-snapshot! target archive bounds opts)]
      (ensure! (= n (:entry-count restored)) :populated-import-count))
    (read! :local-canary)
    (let [activation (elapsed-ns #(eacl/activate-security-key! controller "epoch-1"))
          retirement (elapsed-ns #(eacl/retire-security-key! controller "epoch-0"))
          resident-before (lru/entry-count (:token-store codec))
          cleanup (elapsed-ns #(cursor/cursor->token {:edge -1} cursor-opts))
          recompute (elapsed-ns #(dotimes [i n]
                                   (let [result (read! i)]
                                     (ensure! (and (false? (:cached? result)) (false? (:value result))) :retired-import-miss))))]
      (ensure! (= n resident-before) :retirement-does-not-scan)
      (ensure! (= 1 (lru/entry-count (:token-store codec))) :targeted-codec-cleanup)
      (ensure! (:cached? (read! :local-canary)) :local-answer-retained)
      {:populated-entries-per-store n :activation-ns activation :retirement-ns retirement
       :next-codec-use-with-cleanup-ns cleanup :recompute-imported-entries-ns recompute
       :local-answer-retained? true :resident-before-cleanup resident-before})))

(defn run! [path]
  (let [work (mapv direct-work [1 2 4 16])
        ;; Reverse variant order on alternating sizes to reduce order bias.
        steady (vec (for [n [1 2 4 16] mode (if (#{1 4} n) [:static :live] [:live :static])]
                      (steady-case n mode)))
        populated (vec (for [n [0 64 512] _ (range 5)] (populated-case n)))
        report {:format :eacl.security-keyring/performance-v1
                :java (System/getProperty "java.version") :os (System/getProperty "os.name")
                :arch (System/getProperty "os.arch") :processors (.availableProcessors (Runtime/getRuntime))
                :warmup-operations 2000 :samples 7 :operations-per-sample 1000
                :direct-work work :steady-state steady :populated-stores populated}]
    (io/make-parents path)
    (with-open [writer (io/writer path)] (binding [*out* writer] (pprint/pprint report)))
    {:written path :constant-work-cases (count work) :steady-cases (count steady) :populated-cases (count populated)}))
