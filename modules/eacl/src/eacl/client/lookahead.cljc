(ns eacl.client.lookahead
  "Optional background publication of a served page's continuation.

  After a client-level page operation returns a page with a next page under
  caching, the client may run the same public operation continued after the
  served page's end cursor on a bounded background executor, so the caller's
  next request is an exact rendered-page hit. The background operation is an
  ordinary client call: it selects its own basis, owns its own contract and
  counter ledger, publishes only what the ordinary path publishes, and is
  never visible on the foreground path beyond one bounded submission.
  ClojureScript accepts the option and does nothing."
  #?(:clj (:import [java.util.concurrent
                    SynchronousQueue ThreadFactory ThreadPoolExecutor
                    ThreadPoolExecutor$AbortPolicy TimeUnit])))

(def ^:dynamic *depth*
  "Zero on the foreground path; the continuation depth inside a lookahead
  operation. Read by orchestration for provenance and admission."
  0)

(def option-keys #{:pages :max-inflight})

(defn- invalid-option!
  [value]
  (throw
   (ex-info (str "EACL Config Error: :lookahead must be nil or a map with "
                 "positive integers under :pages and :max-inflight.")
            {:type :eacl/invalid-config
             :eacl/error :eacl/invalid-config
             :key :lookahead
             :value value})))

(defn validate-option!
  "Returns the normalized lookahead option or nil; throws the typed
  configuration error for anything else."
  [value]
  (cond
    (nil? value) nil
    (and (map? value)
         (every? option-keys (keys value))
         (contains? value :pages)
         (contains? value :max-inflight)
         (pos-int? (:pages value))
         (pos-int? (:max-inflight value)))
    (select-keys value option-keys)
    :else (invalid-option! value)))

#?(:clj
   (defn- daemon-thread-factory
     ^ThreadFactory []
     (let [counter (atom 0)]
       (reify ThreadFactory
         (newThread [_ runnable]
           (doto (Thread. ^Runnable runnable
                          (str "eacl-lookahead-" (swap! counter inc)))
             (.setDaemon true)))))))

#?(:clj
   (defn- executor
     "A pool of at most `max-inflight` daemon threads with no queue: a
     submission that finds every thread busy is rejected, which the caller
     turns into a silent drop after releasing its in-flight claim."
     ^ThreadPoolExecutor [max-inflight]
     (ThreadPoolExecutor.
      (int max-inflight) (int max-inflight)
      30 TimeUnit/SECONDS
      (SynchronousQueue.)
      (daemon-thread-factory)
      (ThreadPoolExecutor$AbortPolicy.))))

(defn state
  "Per-client lookahead state for a validated option, or nil when lookahead
  is off or the runtime has no background execution."
  [option]
  #?(:clj (when option
            {:pages (:pages option)
             :max-inflight (:max-inflight option)
             :executor (delay (executor (:max-inflight option)))
             :inflight (atom #{})})
     :cljs (when option nil)))

(defn continuation-request
  "The public request for the page after `page`, or nil when there is none:
  the same normalized request continued after the served end cursor with
  the same forward page size."
  [request page]
  (let [info (:page-info page)
        end-cursor (:end-cursor info)]
    (when (and (map? request)
               (map? info)
               (true? (:has-next-page? info))
               (some? end-cursor)
               (not (false? (:cache? request)))
               (not (contains? request :last))
               (not (contains? request :before)))
      (-> request
          (dissoc :timeout-ms :cancellation-token)
          (assoc :after end-cursor)))))

(defn- submit-background!
  [state operation request page run report]
  #?(:clj
     (when-let [continuation (continuation-request request page)]
       (let [key [operation continuation]
             inflight (:inflight state)
             claimed? (volatile! false)
             _ (swap! inflight (fn [current]
                                 (if (contains? current key)
                                   current
                                   (do (vreset! claimed? true)
                                       (conj current key)))))
             depth (inc *depth*)]
         (if-not @claimed?
           false
           (let [task (fn []
                        (try
                          (binding [*depth* depth]
                            (run continuation))
                          (catch Throwable error
                            (when report
                              (try
                                (report {:operation operation
                                         :provenance :lookahead
                                         :depth depth
                                         :error (or (:type (ex-data error))
                                                    (class error))})
                                (catch Throwable _ nil))))
                          (finally
                            (swap! inflight disj key))))
                 ^ThreadPoolExecutor pool @(:executor state)]
             (try
               (.execute pool ^Runnable task)
               true
               (catch Throwable _
                 (swap! inflight disj key)
                 false))))))
     :cljs
     (do (comment state operation request page run report)
         false)))

(defn submit!
  "Submits the continuation of `page` for `operation` when lookahead is on,
  the page has a next page, the depth budget allows, and no equal
  continuation is in flight. `run` receives the continuation request and
  performs the public operation; its result is discarded and every failure
  reaches only `report` (a function of a map, or nil). Returns true when a
  submission was made, false when it was deduplicated or dropped, and nil
  when lookahead does not apply."
  [state operation request page run report]
  (when (and state (< *depth* (:pages state)))
    (submit-background! state operation request page run report)))
