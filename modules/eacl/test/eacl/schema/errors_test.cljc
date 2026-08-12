(ns eacl.schema.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.schema.errors :as errors]
            [eacl.schema.model :as model]))

(def schema
  {:relations [(model/Relation :document :reader :user)]
   :permissions [(model/Permission :document :view {:relation :reader})]})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest permission-request-schema-errors-are-structured-test
  (testing "resource and subject definitions are distinguished"
    (is (= {:type :eacl/unknown-definition
            :eacl/error :eacl/unknown-definition
            :operation :can?
            :definition :missing
            :position :resource}
           (error-data
            #(errors/validate-permission-request!
              schema :can?
              {:resource-type :missing
               :subject-type :user
               :permission :view}))))
    (is (= :subject
           (:position
            (error-data
             #(errors/validate-permission-request!
               schema :lookup-resources
               {:resource-type :document
                :subject-type :missing
                :permission :view}))))))

  (testing "a missing permission is not collapsed into denial"
    (is (= {:type :eacl/unknown-relation-or-permission
            :eacl/error :eacl/unknown-relation-or-permission
            :operation :count-subjects
            :definition :document
            :relation-or-permission :missing
            :schema-kind :permission
            :permission :missing}
           (error-data
            #(errors/validate-permission-request!
              schema :count-subjects
              {:resource-type :document
               :subject-type :user
               :permission :missing})))))

  (testing "object identifiers are deliberately outside schema validation"
    (is (= schema
           (errors/validate-permission-request!
            schema :can?
            {:resource-type :document
             :subject-type :user
             :permission :view})))))

(deftest relationship-read-schema-errors-are-structured-test
  (is (= :eacl/unknown-definition
         (:type
          (error-data
           #(errors/validate-relationship-read!
             schema {:resource/type :missing})))))
  (is (= :eacl/unknown-relation-or-permission
         (:type
          (error-data
           #(errors/validate-relationship-read!
             schema {:resource/type :document
                     :resource/relation :missing})))))
  (is (= schema
         (errors/validate-relationship-read!
          schema {:subject/type :user
                  :resource/relation :reader}))))
