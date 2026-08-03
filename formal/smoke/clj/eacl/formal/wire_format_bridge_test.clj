(ns eacl.formal.wire-format-bridge-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.formal.wire-format-bridge :as wire]))

(deftest generated-strict-wire-format-test
  (is (= {:status :decoded :page-size 25N}
         (wire/decode-scenario :valid)))

  (testing "ambiguous or unknown fields fail closed"
    (is (= "WireError_DuplicateField"
           (:error-class
            (wire/decode-scenario :duplicate-field))))
    (is (= "WireError_UnknownFieldName"
           (:error-class
            (wire/decode-scenario :unknown-field)))))

  (testing "oversized collections and invalid ranges fail closed"
    (is (= "WireError_OversizedCollection"
           (:error-class
            (wire/decode-scenario :oversized-collection))))
    (is (= "WireError_InvalidRange"
           (:error-class
            (wire/decode-scenario :negative-offset))))
    (is (= "WireError_InvalidRange"
           (:error-class
            (wire/decode-scenario :unsafe-offset))))
    (is (= "WireError_InvalidIdentity"
           (:error-class
            (wire/decode-scenario :invalid-identity))))))
