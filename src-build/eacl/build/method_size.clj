(ns eacl.build.method-size
  "Finds compiled methods too large for the JVM to compile.

  HotSpot interprets a method whose bytecode exceeds 8,000 bytes for the life
  of the process (`-XX:+DontCompileHugeMethods`, the default). Nothing fails
  and no profile says so: the function is only slow, about twenty times.
  A long `loop` with many branches, nested `doseq` bindings and a `case` over
  strings with distant hash codes all reach that size from a few lines of
  Clojure. `bin/reflection-gate` compiles the production namespaces and fails
  on the methods `oversized` finds."
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io DataInputStream File)))

(def huge-method-limit
  "HotSpot's `HugeMethodLimit`: a method must be no larger to be compiled."
  8000)

(defn- skip-attributes!
  "Skips an attribute table and returns the `code_length` of its `Code`
  attribute, when it has one."
  [^DataInputStream in constant-pool]
  (loop [remaining (.readUnsignedShort in)
         code-length nil]
    (if (zero? remaining)
      code-length
      (let [attribute (get constant-pool (.readUnsignedShort in))
            length (.readInt in)]
        (if (= "Code" attribute)
          (do
            ;; max_stack, max_locals, then code_length.
            (.readInt in)
            (let [code-length (.readInt in)]
              (.skipBytes in (- length 8))
              (recur (dec remaining) code-length)))
          (do
            (.skipBytes in length)
            (recur (dec remaining) code-length)))))))

(defn- read-constant-pool
  "The UTF-8 entries of a class file's constant pool, by index."
  [^DataInputStream in]
  (let [size (.readUnsignedShort in)]
    (loop [index 1
           entries {}]
      (if (>= index size)
        entries
        (let [tag (.readUnsignedByte in)]
          (case tag
            1 (recur (inc index) (assoc entries index (.readUTF in)))
            (3 4) (do (.skipBytes in 4) (recur (inc index) entries))
            ;; A long or double takes two entries.
            (5 6) (do (.skipBytes in 8) (recur (+ index 2) entries))
            (7 8 16 19 20) (do (.skipBytes in 2) (recur (inc index) entries))
            (9 10 11 12 17 18) (do (.skipBytes in 4) (recur (inc index) entries))
            15 (do (.skipBytes in 3) (recur (inc index) entries))
            (throw (ex-info "Unknown constant pool tag." {:tag tag}))))))))

(defn method-sizes
  "The bytecode size of every method of one class file:
  `[{:method name :bytes code-length} ...]`."
  [file]
  (with-open [in (DataInputStream. (io/input-stream file))]
    (when-not (= (unchecked-int 0xCAFEBABE) (.readInt in))
      (throw (ex-info "Not a class file." {:file (str file)})))
    (.skipBytes in 4)
    (let [constant-pool (read-constant-pool in)]
      ;; access_flags, this_class, super_class.
      (.skipBytes in 6)
      (.skipBytes in (* 2 (.readUnsignedShort in)))
      (dotimes [_ (.readUnsignedShort in)]
        (.skipBytes in 6)
        (skip-attributes! in constant-pool))
      (let [methods (.readUnsignedShort in)]
        (loop [remaining methods
               sizes []]
          (if (zero? remaining)
            sizes
            (do
              (.skipBytes in 2)
              (let [method (get constant-pool (.readUnsignedShort in))
                    _ (.skipBytes in 2)
                    code-length (skip-attributes! in constant-pool)]
                (recur (dec remaining)
                       (cond-> sizes
                         code-length (conj {:method method
                                            :bytes code-length})))))))))))

(defn- namespace-loader?
  "A namespace's `__init` class runs once, when the namespace loads."
  [class-name]
  (string/ends-with? class-name "__init"))

(defn oversized
  "Methods under `classes-directory` larger than `limit` bytes (by default
  the JVM's), largest first: `[{:class :method :bytes} ...]`. `prefix`
  restricts the classes by path, for example \"eacl/\"."
  ([classes-directory prefix]
   (oversized classes-directory prefix huge-method-limit))
  ([classes-directory prefix limit]
   (let [root (io/file classes-directory)
         root-path (.toPath root)]
     (->> (file-seq root)
          (filter (fn [^File file]
                    (and (.isFile file)
                         (string/ends-with? (.getName file) ".class"))))
          (keep (fn [^File file]
                  (let [relative (str (.relativize root-path (.toPath file)))
                        class-name (-> relative
                                       (subs 0 (- (count relative) 6))
                                       (string/replace File/separator "."))]
                    (when (and (string/starts-with?
                                relative (string/replace prefix "/" File/separator))
                               (not (namespace-loader? class-name)))
                      (keep (fn [{:keys [bytes] :as method}]
                              (when (> bytes limit)
                                (assoc method :class class-name)))
                            (method-sizes file))))))
          (apply concat)
          (sort-by (juxt (comp - :bytes) :class :method))
          vec))))
