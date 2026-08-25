(ns eacl.schema.expression-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.schema.expression :as expression]
            [eacl.secure-format :as secure]))

(defn- error-reason [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (:reason (ex-data error)))))

(def complete-expression
  (expression/expression
    :document
    :view
    (expression/exclusion
      (expression/intersection
        [(expression/union
           [(expression/relation :reader [:service :user])
            (expression/permission :editor true)])
         (expression/arrow
           :parent
           [{:subject-type :folder
             :target-kind :permission
             :target-name :view}
            {:subject-type :organization
             :target-kind :relation
             :target-name :member}])])
      (expression/relation :banned [:user]))))

(deftest closed-expression-round-trip-test
  (let [encoded (expression/encode complete-expression)]
    (is (= complete-expression (expression/decode encoded)))
    (is (= encoded (expression/encode (expression/decode encoded))))
    (is (= (expression/digest complete-expression)
           (expression/digest (expression/decode encoded))))
    (is (= [:service :user]
           (get-in complete-expression
                   [:root :left :children 0 :children 0 :subject-types])))
    (is (= [:folder :organization]
           (mapv :subject-type
                 (get-in complete-expression
                         [:root :left :children 1 :partitions]))))))

(deftest constructors-reject-invalid-shapes-test
  (is (= :duplicate-subject-type
         (error-reason #(expression/relation :reader [:user :user]))))
  (is (= :invalid-operator-arity
         (error-reason #(expression/union
                          [(expression/permission :view)]))))
  (is (= :duplicate-arrow-subject-type
         (error-reason
           #(expression/arrow
              :parent
              [{:subject-type :folder
                :target-kind :permission
                :target-name :view}
               {:subject-type :folder
                :target-kind :relation
                :target-name :reader}]))))
  (is (= :invalid-identifier
         (error-reason #(expression/permission :qualified/view)))))

(deftest codec-rejects-unknown-version-tag-and-fields-test
  (testing "unknown format"
    (is (= :unsupported-format
           (error-reason
             #(expression/decode
                (secure/encode-canonical
                  (assoc complete-expression
                         :format :eacl.permission-expression/v2)))))))

  (testing "unknown top-level field"
    (is (= :malformed-codec
           (error-reason
             #(expression/decode
                (secure/encode-canonical
                  (assoc complete-expression :forged true)))))))

  (testing "unknown node tag"
    (is (= :unknown-node-tag
           (error-reason
             #(expression/decode
                (secure/encode-canonical
                  (assoc complete-expression :root
                         {:op :complement :grouped? false})))))))

  (testing "unknown node field"
    (is (= :unknown-or-missing-fields
           (error-reason
             #(expression/decode
                (secure/encode-canonical
                  (update complete-expression :root assoc :forged true)))))))

  (testing "noncanonical spelling"
    (is (= :noncanonical-encoding
           (error-reason
             #(expression/decode
                (str (expression/encode complete-expression) " ")))))))
