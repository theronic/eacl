(ns eacl.datascript.qualifier-sweep-mutation-test
  (:require [clojure.test :refer [deftest is]]
            [eacl.datascript.qualifier-storage-test :as native]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.qualifier-sweep-contract :as contract]))

(deftest native-work-contract-kills-repeated-global-capture
  (let [original @#'integrity/sweep-chunk!]
    (with-redefs-fn
      {#'integrity/sweep-chunk!
       (fn [native certificate chunk]
         ((:with-snapshot native) #(integrity/proof-input native %))
         (original native certificate chunk))}
      #(native/with-sweep-fixture
        (fn [fixture]
          (let [{:keys [result counts expected]} (contract/work-outcome fixture)]
            (is (= expected (set (:qualifiers result))))
            (is (> (:streams counts) 6) "the native work contract detects per-chunk recapture")))))))

(deftest native-attachment-contract-kills-unproved-rebase
  (let [original @#'integrity/sweep-chunk!]
    (with-redefs-fn
      {#'integrity/sweep-chunk!
       (fn [native _certificate chunk]
         (let [unproved ((:with-snapshot native) #(@#'integrity/sweep-certificate native %))]
           (original native unproved chunk)))}
      #(native/with-sweep-fixture
        (fn [fixture]
          (let [{:keys [attached attached-survives?]}
                (contract/hostile-outcome fixture :attachment)]
            (is (= 1 (count attached)))
            (is (false? attached-survives?) "unproved rebasing deletes a newly attached qualifier")))))))
