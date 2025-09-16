(ns eacl.datomic.spice-parser
  "WIP."
  (:require [instaparse.core :as insta]
            [clojure.pprint]
            [clojure.string]
            [eacl.datomic.core]
            [eacl.datomic.impl :as impl]))

;      primary-expr = identifier | <'('> permission-expr <')'>
;; Define the SpiceDB grammar with auto-whitespace
(def spicedb-parser
  (insta/parser
    "schema = definition+

     definition = <'definition'> identifier <'{'> definition-body <'}'>
     definition-body = (relation | permission)*

     relation-name = identifier
     relation-subject-type = identifier
     relation = <'relation'> relation-name <':'> relation-subject-type

     <permission-name> = identifier
     permission = <'permission'> permission-name <'='> permission-expr

     permission-operator = '+' | '-'

     permission-expr = direct-or-arrow (permission-operator direct-or-arrow)*

     <permission-relation> = identifier

     direct-permission = permission-relation

     <arrow-via-permission> = identifier
     <arrow-relation> = identifier
     arrow-permission = arrow-relation <'->'> arrow-via-permission

     <direct-or-arrow> = direct-permission | arrow-permission

     <identifier> = #'[a-zA-Z_][a-zA-Z0-9_]*'"
    :auto-whitespace :standard))

;; Example SpiceDB schema
(def example-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = owner + admin
     permission update = admin
   }")

;; Parse the schema
(defn parse-schema [schema-str]
  (spicedb-parser schema-str))

;; Pretty print parse tree
(defn pretty-print-tree [tree]
  (clojure.pprint/pprint tree))

;; Extract relations from definition body
(defn extract-relations [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
         (filter #(and (vector? %) (= :relation (first %))))
         (map (fn [[_ rel-name type-name]]
                [(second rel-name) (keyword (second type-name))]))
         (into {}))
    []))

;; Extract permissions from definition body
(defn extract-permissions [definition-body]
  (if (and (vector? definition-body) (= :definition-body (first definition-body)))
    (->> (rest definition-body)
         (filter #(and (vector? %) (= :permission (first %))))
         (map (fn [[_ perm-name expr]]
                {:name       perm-name ; this was (second perm-name), why?
                 :expression expr})))
    []))

;; Extract definitions from parse tree
(defn extract-definitions [parse-tree]
  (->> parse-tree
       (filter #(and (vector? %) (= :definition (first %))))
       (map (fn [[_ name definition-body]]
              ; why was this (second name)?
              [name {:relations   (extract-relations definition-body)
                     :permissions (extract-permissions definition-body)}]))
       (into {})))

;; Transform parse tree to more usable data structure
(defn transform-schema
  "This just calls extract-definitions."
  [parse-tree]
  (when (and (vector? parse-tree) (= :schema (first parse-tree)))
    {:definitions (extract-definitions (rest parse-tree))}))

;; Helper to parse expressions
(defn parse-permission-expression [expr-str]
  (let [full-schema (str "definition temp { permission test = " expr-str " }")
        parsed (spicedb-parser full-schema)]
    (if (insta/failure? parsed)
      (do
        (println "Failed to parse expression:" expr-str)
        (println "Error:" (insta/get-failure parsed))
        nil)
      ;; Path: schema -> definition -> definition-body -> permission -> permission-expr
      (get-in parsed [1 2 1 2]))))

;; Transform expressions to a more usable format
(defn transform-expression [expr]
  (cond
    (vector? expr)
    (case (first expr)
      :union-expr (let [operands (rest expr)]
                    (if (= 1 (count operands))
                      (transform-expression (first operands))
                      {:type :union
                       :operands (map transform-expression operands)}))
      :arrow-expr (let [parts (rest expr)]
                    (if (= 1 (count parts))
                      (transform-expression (first parts))
                      {:type :arrow
                       :base (transform-expression (first parts))
                       :path (map #(second %) (rest parts))}))
      :primary-expr (transform-expression (second expr))
      :identifier {:type :identifier :name (second expr)}
      :permission-expr (transform-expression (second expr))
      expr)
    :else expr))

;; Pretty print expressions in a readable format
(defn format-expression [expr]
  (cond
    (map? expr)
    (case (:type expr)
      :union (str "(" (clojure.string/join " + " (map format-expression (:operands expr))) ")")
      :arrow (str (format-expression (:base expr)) "->" (clojure.string/join "->" (:path expr)))
      :identifier (:name expr)
      (str expr))
    :else (str expr)))

;; Analyze a specific definition
(defn analyze-definition [schema-str def-name]
  (let [parsed (parse-schema schema-str)]
    (when-not (insta/failure? parsed)
      (let [definitions (extract-definitions (rest parsed))
            target-def (first (filter #(= def-name (:name %)) definitions))]
        (when target-def
          (println (str "Definition: " def-name))
          (println "Relations:")
          (doseq [rel (:relations target-def)]
            (println (str "  " (:name rel) " : " (:type rel))))
          (println "Permissions:")
          (doseq [perm (:permissions target-def)]
            (println (str "  " (:name perm) " = "
                          (format-expression (transform-expression (:expression perm))))))
          target-def)))))

;; Usage examples
(defn demo []
  (println "=== Parsing SpiceDB Schema ===")

  ;; Parse the full schema
  (let [parsed (parse-schema example-schema)]
    (if (insta/failure? parsed)
      (do
        (println "Parse failed:")
        (println (insta/get-failure parsed)))
      (do
        (println "\n1. Schema structure:")
        (let [transformed (transform-schema parsed)]
          (doseq [def (:definitions transformed)]
            (println (str "- " (:name def)
                          " (" (count (:relations def)) " relations, "
                          (count (:permissions def)) " permissions)"))))

        (println "\n2. Detailed breakdown:")
        (doseq [def-name ["user" "platform" "account"]]
          (analyze-definition example-schema def-name)
          (println)))))


  (rest (get (parse-permission-expression "owner + platform->super_admin") 1))

  ;; Parse individual expressions
  (println "\n=== Parsing Individual Expressions ===")
  (doseq [expr ["owner + admin"
                "platform->super_admin"
                "owner + platform->super_admin"
                "account->admin + vpc->admin + shared_admin"]]
    (let [parsed-expr (parse-permission-expression expr)]
      (if parsed-expr
        (do
          (println (str "Expression: " expr))
          (println (str "Parsed as: " (format-expression (transform-expression parsed-expr))))
          (println (str "Structure: " (transform-expression parsed-expr)))
          (println))
        (println (str "Failed to parse: " expr)))))

  ;; Demonstrate error handling
  (println "\n=== Error Handling ===")
  (let [bad-schema "definition user { invalid syntax }"]
    (let [result (parse-schema bad-schema)]
      (if (insta/failure? result)
        (println "Parse error (expected):" (insta/get-failure result))
        (println "Unexpected success")))))

(defn ->eacl-schema [parse-tree]) ; wip

(defn extract-expr [permission-exp]
  (-> permission-exp
      (get 1)
      (rest)))

;; Run the demo
(comment
  (def *defs
    (let [parse-tree (parse-schema example-schema)]
      (:definitions (transform-schema parse-tree))))

  (doall
    (for [[resource-type spec :as definition] *defs]
      (do
        (prn 'definition definition)
        (let [{:keys [relations permissions]} spec]
          (concat
            (for [{:as           relation
                   relation-name :name
                   subject-type  :type} relations]
              (do
                (prn 'relation relation)
                (impl/Relation (keyword resource-type) (keyword relation-name) (keyword subject-type))))
            (for [{:as             permission
                   permission-name :name
                   expr            :expression} permissions]
              (extract-expr expr)))))))

  (demo)
  (analyze-definition example-schema "account"))