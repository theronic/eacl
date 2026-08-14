(ns eacl.formal.verified-authority-test-runner
  "Runs the complete CLJS/DataScript suite with portable authority injected
  into every client that did not explicitly select an engine."
  (:require
   [cljs.nodejs :as nodejs]
   [cljs.test :as t]
   [eacl.backend.v8-test]
   [eacl.cache-test]
   [eacl.causal-model-test]
   [eacl.consistency-test]
   [eacl.datascript.adapter-certification-test]
   [eacl.datascript.consistency-v3-test]
   [eacl.datascript.contract-test]
   [eacl.datascript.core :as datascript]
   [eacl.datascript.impl-test]
   [eacl.datascript.storage-test]
   [eacl.engine.relationships-test]
   [eacl.execution-test]
   [eacl.formal.cache-strategy-adversarial-test]
   [eacl.formal.differential-runner-test]
   [eacl.formal.generators-test]
   [eacl.formal.production-kernel-cljs :as production]
   [eacl.relationships.endpoint-pair-test]
   [eacl.relay-test]
   [eacl.secure-format-test]
   [eacl.subproblem-cache-test]
   [eacl.verified-kernel :as verified]
   [eacl.verified-kernel-test]))

(nodejs/enable-util-print!)

(def calls (atom {}))
(def injected-clients (atom 0))

(def required-portable-authority-operations
  #{:recursive-routing-certificate
    :enumeration-route
    :acyclic-page
    :acyclic-continuation
    :acyclic-count
    :acyclic-work
    :indexed-traversal-compile
    :indexed-traversal-initialize
    :indexed-traversal-drive
    :indexed-traversal-read})

(defn- count-call!
  [operation]
  (swap! calls update operation (fnil inc 0)))

(defrecord CountingKernel [delegate]
  verified/DecisionKernel
  (-decide [_ operation input]
    (count-call! operation)
    (verified/-decide delegate operation input))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (count-call! :indexed-traversal-compile)
    (verified/-compile-indexed-plan delegate input))
  (-initialize-indexed [_ direction input]
    (count-call! :indexed-traversal-initialize)
    (verified/-initialize-indexed delegate direction input))
  (-drive-indexed [_ direction state limits fuel]
    (count-call! :indexed-traversal-drive)
    (verified/-drive-indexed delegate direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (count-call! :indexed-traversal-resume)
    (verified/-resume-indexed delegate direction state response limits))
  (-continue-indexed-page [_ direction state input]
    (count-call! :indexed-traversal-continue)
    (verified/-continue-indexed-page delegate direction state input))
  (-read-indexed-result [_ direction state]
    (count-call! :indexed-traversal-read)
    (verified/-read-indexed-result delegate direction state)))

(def selection
  {:kernel
   (->CountingKernel production/portable-cljs-kernel)})

(def original-make-client datascript/make-client)

(defn- injecting-make-client
  [connection options]
  (swap! injected-clients inc)
  ;; Production exposes no kernel selector. The formal runner wraps the
  ;; released portable default only for the synchronous constructor call.
  (with-redefs [production/default-selection selection]
    (original-make-client connection (or options {}))))

(defmethod t/report [::t/default :end-run-tests]
  [summary]
  (let [missing-required-operations
        (filterv
         #(zero? (get @calls % 0))
         required-portable-authority-operations)
        authority-failures
        (+ (if (pos? @injected-clients) 0 1)
           (if (pos? (reduce + 0 (vals @calls))) 0 1)
           (count missing-required-operations))
        failures
        (+ (:fail summary 0)
           (:error summary 0)
           authority-failures)]
    (.log
     js/console
     (str "EACL DataScript CLJS verified-authority tests complete. "
          "failures=" (:fail summary 0)
          " errors=" (:error summary 0)
          " injected-clients=" @injected-clients
          " portable-calls=" (pr-str @calls)
          " missing-required-portable-operations="
          (pr-str missing-required-operations)
          " authority-gate-failures=" authority-failures))
    (js/process.exit failures)))

(defn -main
  []
  (reset! calls {})
  (reset! injected-clients 0)
  (with-redefs [datascript/make-client injecting-make-client]
    (t/run-tests
     'eacl.backend.v8-test
     'eacl.cache-test
     'eacl.causal-model-test
     'eacl.consistency-test
     'eacl.engine.relationships-test
     'eacl.execution-test
     'eacl.relationships.endpoint-pair-test
     'eacl.relay-test
     'eacl.secure-format-test
     'eacl.subproblem-cache-test
     'eacl.verified-kernel-test
     'eacl.formal.cache-strategy-adversarial-test
     'eacl.formal.differential-runner-test
     'eacl.formal.generators-test
     'eacl.datascript.adapter-certification-test
     'eacl.datascript.consistency-v3-test
     'eacl.datascript.contract-test
     'eacl.datascript.impl-test
     'eacl.datascript.storage-test)))

(set! *main-cli-fn* -main)
