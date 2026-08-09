(ns eacl.core-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.core :as eacl]))

(defrecord LegacyAuthorization [decision]
  eacl/IAuthorization
  (can? [_ _ _ _] decision)
  (can? [_ _ _ _ _] decision)
  (can? [_ _] decision)
  (read-schema [_] nil)
  (write-schema! [_ _] nil)
  (read-relationships [_ _] nil)
  (write-relationships! [_ _] nil)
  (write-relationship! [_ _ _ _ _] nil)
  (write-relationship! [_ _] nil)
  (create-relationships! [_ _] nil)
  (create-relationship! [_ _ _ _] nil)
  (create-relationship! [_ _] nil)
  (delete-relationships! [_ _] nil)
  (delete-object! [_ _] nil)
  (delete-relationship! [_ _ _ _] nil)
  (delete-relationship! [_ _] nil)
  (lookup-resources [_ _] nil)
  (count-resources [_ _] nil)
  (lookup-subjects [_ _] nil)
  (count-subjects [_ _] nil)
  (expand-permission-tree [_ _] nil))

(deftest detailed-permission-fallback-test
  (testing "existing IAuthorization implementations need no new protocol methods"
    (let [demand {:subject {:type :user :id "user-1"}
                  :permission :view
                  :resource {:type :document :id "document-1"}}]
      (is (= {:allowed? true
              :cached? false
              :cache-basis nil}
             (eacl/check-permission
              (->LegacyAuthorization true)
              demand)))
      (is (= {:allowed? false
              :cached? false
              :cache-basis nil}
             (eacl/check-permission
              (->LegacyAuthorization false)
              (:subject demand)
              (:permission demand)
              (:resource demand)))))))
