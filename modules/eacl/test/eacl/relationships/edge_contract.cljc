(ns eacl.relationships.edge-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.request.counters :as counters]
            [eacl.authorization.qualification :as qualification]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]
            [eacl.relationships.storage :as storage]))

(def schema "definition user {}\ndefinition doc {\n relation viewer: user\n permission view = viewer\n}")

(defn error-type [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) error (:eacl/error (ex-data error)))))

(defn check! [{:keys [write-schema! writer entid forward reverse direct adapter with-basis]}]
  (write-schema! schema)
  (let [w (writer) native (:native w) snapshot (:snapshot native) tx! (:transact! native)
        objects (mapv #(hash-map :eacl/id (str "edge/" %)) (range 12))]
    (tx! objects)
    (let [db (snapshot)
          ids (mapv #(entid db [:eacl/id (str "edge/" %)]) (range 12))
          subject (nth ids 0) other (nth ids 1) resources (vec (sort (subvec ids 2)))
          relation (entid db [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])]
      (doseq [[i resource] (map-indexed vector resources)]
        (staged/write! w :create [:user subject relation :doc resource]
                       (when (even? i) {:valid-until-ms (+ 100 i)})))
      (staged/write! w :create [:user other relation :doc (first resources)] nil)
      ((or with-basis
           (fn [f] ((:with-snapshot native) #(f % (adapter %)))))
       (fn [database native-adapter]
         (let [opts {:direction :asc :include-qualifier? true}
               edges (vec (forward database :user subject relation :doc opts))
               selected (first resources)
               first-q (edge/qualifier-id (first edges))]
           (is (= resources (mapv edge/endpoint edges)))
           (is (= 5 (count (filter vector? edges))))
           (doseq [time [99 104]]
             (let [reads (atom {}) packets (atom {}) ledger (counters/make-ledger)
                   base (qualification/request-from-adapter
                         native-adapter
                         {:time time :basis {:source ((:source native) database)
                                             :revision ((:revision native) database)}})
                   request (assoc base :lookup
                                  (fn [eid]
                                    (swap! reads update eid (fnil inc 0))
                                    (let [packet ((:lookup base) eid)]
                                      (swap! packets assoc eid packet)
                                      packet)))]
               (counters/call-with-ledger
                ledger
                (fn []
                  (is (true? (qualification/qualify request relation selected)))
                  (is (= {} @reads))
                  (is (every? zero? (vals (counters/snapshot ledger))))
                  (doseq [[i e] (map-indexed vector edges)]
                    (let [expected (or (odd? i) (< time (+ 100 i)))]
                      (is (= expected (evidence/has? (qualification/qualify request relation e))))
                      (is (= expected (evidence/has? (qualification/qualify request relation e))))))))
               (is (every? #(= 1 (get @reads %)) (keep edge/qualifier-id edges)))
               (is (= 6 (count @reads)))
               (is (= 6 (:commands (counters/snapshot ledger))))
               (is (= 6 (:adapter-reads (counters/snapshot ledger))))
               (is (= (reduce + (map :fact-count (vals @packets)))
                      (:fetched-values (counters/snapshot ledger))))
               (is (every? #(= ((:qualifier-version native) database %)
                                (:version (get @packets %)))
                           (keep edge/qualifier-id edges)))))
           (is (every? edge/valid? edges))
           (is (= (vec (rseq edges)) (vec (forward database :user subject relation :doc (assoc opts :direction :desc)))))
           (doseq [direction [:asc :desc] inclusive? [true false] bound resources]
             (let [expected (filterv (fn [eid]
                                       ((if (= direction :asc)
                                          (if inclusive? >= >) (if inclusive? <= <)) eid bound))
                                     (if (= direction :asc) resources (vec (rseq resources))))
                   actual (forward database :user subject relation :doc
                                   (assoc opts :direction direction :bound-eid bound :inclusive-bound? inclusive?))]
               (is (= expected (mapv edge/endpoint actual)))))
           (doseq [[resource e] (map vector resources edges)]
             (is (= e (direct database :user subject relation :doc resource))))
           (let [back (vec (reverse database :doc selected relation :user opts))]
             (is (= (vec (sort [subject other])) (mapv edge/endpoint back)))
             (is (= first-q (edge/qualifier-id (first (filter #(= subject (edge/endpoint %)) back))))))
           (is (nil? (direct database :user subject relation :doc other)))
           (is (= :eacl/unsupported-qualifier
                  (error-type #(vec (forward database :user subject relation :doc {:direction :asc})))))
           (is (= first-q (nth (:v (first ((:rows native) database subject storage/forward-attribute
                                          [:user relation :doc selected nil]))) 4)))))))))
