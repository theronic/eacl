(ns eacl.formal.java-round-trip-test
  (:require
   [clojure.test :refer [deftest is]]
   [eacl.formal.java-round-trip :as round-trip])
  (:import
   (dafny DafnyMap DafnySequence DafnySet TypeDescriptor)))

(deftype CollidingKey [id]
  Object
  (equals [_ other]
    (and (instance? CollidingKey other)
         (= id (.-id ^CollidingKey other))))
  (hashCode [_] 17))

(defn- integer-sequence
  [values]
  (DafnySequence/fromList
   TypeDescriptor/BIG_INTEGER
   (mapv biginteger values)))

(deftest generated-java-value-collection-and-error-round-trip
  (is (= {:status :accepted
          :values [0N 7N 42N]}
         (round-trip/round-trip
          "eacl.round-trip/v1"
          [0 7 42]
          3)))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "unknown"
           [1]
           1))))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "eacl.round-trip/v1"
           [1 -1]
           2))))
  (is (= :rejected
         (:status
          (round-trip/round-trip
           "eacl.round-trip/v1"
           [1 2]
           1)))))

(deftest generated-java-persistent-collection-boundary
  (let [first-key (integer-sequence [1 2])
        equal-first-key (integer-sequence [1 2])
        second-key (integer-sequence [3 4])
        first-set
        (DafnySet.
         ^java.util.Collection
         (java.util.ArrayList. [first-key]))
        expanded-set
        (DafnySet/union
         first-set
         (DafnySet.
          ^java.util.Collection
          (java.util.ArrayList. [second-key])))
        first-map (DafnyMap. ^java.util.Map {first-key "first"})
        updated-map
        (DafnyMap/update first-map equal-first-key "updated")
        colliding-set
        (DafnySet.
         ^java.util.Collection
         (java.util.ArrayList.
          [(CollidingKey. 1) (CollidingKey. 2)]))]
    (is (.contains first-set equal-first-key))
    (is (not (.contains first-set second-key)))
    (is (.contains expanded-set equal-first-key))
    (is (.contains expanded-set second-key))
    (is (= "first" (.get first-map equal-first-key)))
    (is (= "updated" (.get updated-map first-key)))
    (is (= 1 (.size updated-map)))
    (is (= 2 (.size colliding-set)))
    (is (.contains colliding-set (CollidingKey. 1)))
    (is (.contains colliding-set (CollidingKey. 2)))))
