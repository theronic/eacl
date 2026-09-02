(ns eacl.datahike.io
  "Optional storage I/O statistics for Datahike deployments.

  The S3 storage backend used by EACL's demos carries its own opt-in
  operation counters (`konserve-s3.core/set-global-io-stats!` and
  `io-stats-summary`). This namespace exposes them without a dependency:
  the functions are resolved at call time and, when absent, the body runs
  unchanged and storage statistics are reported as `:unavailable`. The
  global accumulator is not reentrant, so wrap one probe at a time. EACL
  never calls this on a production request path.")

(defn- resolve-fn
  [symbol]
  (try
    (requiring-resolve symbol)
    (catch Throwable _ nil)))

(defn storage-io-stats-available?
  "True when the S3 storage backend's I/O statistics can be captured."
  []
  (boolean (and (resolve-fn 'konserve-s3.core/set-global-io-stats!)
                (resolve-fn 'konserve-s3.core/io-stats-summary))))

(defn call-with-storage-io-stats
  "Runs `f` and returns `{:value (f) :storage-io summary}` where `summary`
  maps each storage operation to its count and latency percentiles, or
  `{:value (f) :storage-io :unavailable}` when the backend statistics are
  absent from the classpath."
  [f]
  (let [install! (resolve-fn 'konserve-s3.core/set-global-io-stats!)
        summarize (resolve-fn 'konserve-s3.core/io-stats-summary)]
    (if-not (and install! summarize)
      {:value (f) :storage-io :unavailable}
      (let [accumulator (atom {})]
        (install! accumulator)
        (try
          (let [value (f)]
            {:value value :storage-io (summarize @accumulator)})
          (finally
            (install! nil)))))))

(defmacro with-storage-io-stats
  "Evaluates `body` under `call-with-storage-io-stats`."
  [& body]
  `(call-with-storage-io-stats (fn [] ~@body)))
