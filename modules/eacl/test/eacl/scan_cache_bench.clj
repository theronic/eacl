(ns eacl.scan-cache-bench
  "Paired same-process measurement of the scan-response cache and the page
  shell on one client: a sweep over users with the shared tier disabled and
  enabled, interleaved trials, medians, and adapter-command counts through
  the invoke observer. Backend modules supply the seeded client; the CI smoke
  test runs one small fixture per backend, the gate the full one."
  (:require [eacl.backend.v8 :as backend]
            [eacl.scan-cache-fixture :as fixture]))

(defn- median
  [values]
  (let [sorted (vec (sort values))
        n (count sorted)]
    (if (zero? n)
      0.0
      (double (nth sorted (quot n 2))))))

(defn- sweep
  "One sweep of `:first n` pages over every user; returns
  {:us-per-page median-microseconds :commands total-adapter-scan-commands}."
  [client {:keys [users page-size cache?]}]
  (let [commands (atom 0)
        observer (fn [{:keys [phase operation]}]
                   (when (and (= :after phase)
                              (contains? #{:subject->resources
                                           :resource->subjects}
                                         operation))
                     (swap! commands inc)))
        durations
        (binding [backend/*invoke-observer* observer]
          (mapv (fn [u]
                  (let [start (System/nanoTime)]
                    (fixture/page client u page-size :cache? cache?)
                    (/ (- (System/nanoTime) start) 1000.0)))
                (range users)))]
    {:us-per-page (median durations)
     :commands @commands}))

(defn paired-run
  "Runs interleaved sweeps on `disabled-client` (tier off) and
  `enabled-client` (tier on) seeded with the same fixture and returns the
  medians of `trials` sweeps per mode plus elision. `warm-ups` sweeps run
  first on both clients and are discarded."
  [{:keys [disabled-client enabled-client users page-size trials warm-ups
           cache-stats-fn]
    :or {page-size 20 trials 5 warm-ups 2}}]
  (let [trial-counter (atom 0)
        ;; Every sweep uses a page size no earlier sweep used, so the answer
        ;; and rendered-page tiers miss and only scan reuse is measured.
        run (fn [client]
              (let [size (+ page-size (swap! trial-counter inc))]
                (sweep client {:users users :page-size size :cache? true})))]
    (dotimes [_ warm-ups] (run disabled-client) (run enabled-client))
    (let [results (vec (repeatedly trials
                                   (fn []
                                     ;; Alternate the order so drift affects both.
                                     [(run disabled-client) (run enabled-client)
                                      (run enabled-client) (run disabled-client)])))
          disabled (mapcat (fn [[a _ _ d]] [a d]) results)
          enabled (mapcat (fn [[_ b c _]] [b c]) results)
          disabled-commands (median (map :commands disabled))
          enabled-commands (median (map :commands enabled))]
      {:disabled {:us-per-page (median (map :us-per-page disabled))
                  :commands-per-sweep disabled-commands}
       :enabled {:us-per-page (median (map :us-per-page enabled))
                 :commands-per-sweep enabled-commands}
       :elision (if (pos? disabled-commands)
                  (- 1.0 (/ enabled-commands disabled-commands))
                  0.0)
       :scan-cache (when cache-stats-fn
                     (:scan-cache (cache-stats-fn enabled-client)))})))
