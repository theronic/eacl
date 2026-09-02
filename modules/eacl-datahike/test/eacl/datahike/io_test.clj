(ns eacl.datahike.io-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.datahike.io :as io]))

(defn- with-stub-backend
  "Installs a stub `konserve-s3.core` namespace carrying the two statistics
  functions for the duration of `f`, then removes it."
  [f]
  (let [ns-symbol 'konserve-s3.core
        installed (atom nil)]
    (create-ns ns-symbol)
    (intern ns-symbol 'set-global-io-stats! (fn [acc] (reset! installed acc)))
    (intern ns-symbol 'io-stats-summary
            (fn [m] (into {} (for [[op {:keys [n]}] m] [op {:n n}]))))
    (try
      (f installed)
      (finally
        (remove-ns ns-symbol)))))

(deftest storage-statistics-are-unavailable-without-the-backend-test
  (when-not (find-ns 'konserve-s3.core)
    (is (false? (io/storage-io-stats-available?)))
    (is (= {:value 42 :storage-io :unavailable}
           (io/with-storage-io-stats 42)))))

(deftest storage-statistics-are-captured-with-the-backend-test
  (with-stub-backend
    (fn [installed]
      (is (true? (io/storage-io-stats-available?)))
      (testing "the accumulator is installed around the body and removed after"
        (let [result (io/with-storage-io-stats
                       (swap! @installed assoc :get {:n 3})
                       :done)]
          (is (= {:value :done :storage-io {:get {:n 3}}} result))
          (is (nil? @installed))))
      (testing "a throwing body still removes the accumulator"
        (is (thrown? clojure.lang.ExceptionInfo
                     (io/with-storage-io-stats (throw (ex-info "x" {})))))
        (is (nil? @installed))))))
