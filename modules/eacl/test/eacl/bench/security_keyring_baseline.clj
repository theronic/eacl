(ns eacl.bench.security-keyring-baseline
  "Cross-version static-ring timing fixture; load by absolute path in the parent checkout."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [eacl.cursor :as cursor]
            [eacl.core]))

(defn sample [f]
  (let [start (System/nanoTime)]
    (dotimes [_ 1000] (f))
    (/ (double (- (System/nanoTime) start)) 1000.0)))

(defn certify! [path & [mode]]
  (let [results
        (mapv (fn [n]
                (let [keys (into {} (map (fn [i] [(str "epoch-" i) (vec (repeat 32 (inc i)))]) (range n)))
                      opts (merge {:cursor-construction-cache (cursor/codec-cache {:max-entries 64})}
                                  (if (= :live mode)
                                    {:keyring-controller ((ns-resolve 'eacl.core 'security-keyring)
                                                          {:keys keys :active-kid "epoch-0"})}
                                    {:keyring keys :current-kid "epoch-0"}))
                      token (cursor/cursor->token {:edge 7} opts)
                      mint #(cursor/cursor->token {:edge 7} opts)
                      decode #(cursor/token->cursor token opts)]
                  (dotimes [_ 2000] (mint) (decode))
                  (into {:ring-size n}
                        (for [[op f] [[:mint mint] [:decode decode]]]
                          (let [samples (vec (repeatedly 7 #(sample f)))]
                            [op {:median-ns-per-operation (nth (vec (sort samples)) 3)
                                 :samples-ns-per-operation samples}])))))
              [1 2 4 16])]
    (io/make-parents path)
    (with-open [writer (io/writer path)]
      (binding [*out* writer]
        (pprint/pprint {:format :eacl.security-keyring/static-baseline-v1
                        :warmup-operations 2000 :samples 7 :operations-per-sample 1000
                        :java (System/getProperty "java.version") :steady-state results})))
    {:written path :cases (count results)}))
