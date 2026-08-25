(ns eacl.operator.batch-schedule
  "Pure demand-sized exponential candidate-vector schedule.")

(def maximum-width 256)

(defn- natural! [field value]
  (when-not (and (integer? value) (not (neg? value)))
    (throw
     (ex-info "Batch schedule dimensions must be natural integers."
              {:type :eacl.operator/invalid-batch-schedule
               :eacl/error :eacl.operator/invalid-batch-schedule
               :field field :value value})))
  value)

(defn initial
  "Creates the first schedule state. Result demand includes any sentinel."
  [result-demand candidate-window]
  (natural! :result-demand result-demand)
  (natural! :candidate-window candidate-window)
  {:version 1
   :remaining-demand result-demand
   :remaining-window candidate-window
   :next-width (min maximum-width result-demand candidate-window)
   :previous-width 0
   :examined 0
   :accepted 0})

(defn advance
  "Advances after one complete vector. Rejection is the only reason to grow;
  the next vector remains bounded by the unresolved result demand, remaining
  candidate window, and the physical cap."
  [state issued-width accepted-count]
  (natural! :issued-width issued-width)
  (natural! :accepted-count accepted-count)
  (when-not (= issued-width (:next-width state))
    (throw
     (ex-info "Issued batch width does not match the sealed schedule."
              {:type :eacl.operator/invalid-batch-schedule
               :eacl/error :eacl.operator/invalid-batch-schedule
               :expected (:next-width state)
               :actual issued-width})))
  (when (> accepted-count issued-width)
    (throw
     (ex-info "Accepted count exceeds the issued candidate vector."
              {:type :eacl.operator/invalid-batch-schedule
               :eacl/error :eacl.operator/invalid-batch-schedule
               :issued-width issued-width
               :accepted-count accepted-count})))
  (let [remaining-demand (max 0 (- (:remaining-demand state)
                                    accepted-count))
        remaining-window (- (:remaining-window state) issued-width)
        rejected (- issued-width accepted-count)
        desired (if (pos? rejected)
                  (min maximum-width (* 2 issued-width))
                  remaining-demand)
        next-width (if (or (zero? remaining-demand)
                           (zero? remaining-window))
                     0
                     (min maximum-width remaining-window
                          (max remaining-demand desired)))]
    (-> state
        (assoc :remaining-demand remaining-demand
               :remaining-window remaining-window
               :next-width next-width
               :previous-width issued-width)
        (update :examined + issued-width)
        (update :accepted + accepted-count))))

(defn done? [state]
  (zero? (:next-width state)))
