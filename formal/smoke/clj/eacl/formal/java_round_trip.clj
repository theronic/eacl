(ns eacl.formal.java-round-trip
  (:import
   (dafny DafnySequence TypeDescriptor)
   (EaclKernel WireResult_Accepted WireResult_Rejected __default)))

(defn- dafny-string
  [s]
  (DafnySequence/asUnicodeString s))

(defn- dafny-integers
  [xs]
  (DafnySequence/fromList
   TypeDescriptor/BIG_INTEGER
   (mapv biginteger xs)))

(defn round-trip
  [tag values max-values]
  (let [result (__default/RoundTrip
                (dafny-string tag)
                (dafny-integers values)
                (biginteger max-values))]
    (cond
      (instance? WireResult_Accepted result)
      {:status :accepted
       :values (mapv bigint (.dtor_items result))}

      (instance? WireResult_Rejected result)
      {:status :rejected
       :error (str (.dtor_error result))}

      :else
      (throw
       (ex-info
        "Generated Dafny boundary returned an unknown result variant."
        {:eacl/error :eacl.formal/unknown-generated-result
         :class (class result)})))))
