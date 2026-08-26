(ns eacl.operator-engine.union-only-baseline-test
  "Exact pre-operator public behavior and decoded cursor-payload baselines.

  Unlike the historical stable-discovery comparison, this gate deliberately
  retains union-only result order and engine-visible cursor payloads.  Opaque
  ciphertext is randomized and therefore is not an equality oracle."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.baseline.capture :as baseline]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.datascript.core :as datascript]
            [eacl.secure-format :as secure]
            [eacl.test-support.repo :as repo])
  (:import (java.nio.file Files)
           (java.security MessageDigest)))

(def baseline-index-file
  "exploration/operator-engine/union-only-baseline.edn")

(def cursor-snapshot-file
  "exploration/operator-engine/union-only-cursor-payloads.edn")

(def ^:private baseline-security-key
  "operator-engine-union-baseline-key")

(def ^:private cursor-format-options
  {:current-kid :default
   :keyring {:default (secure/normalize-key baseline-security-key)}})

(defn- object-ref [{:keys [type id]}]
  (str (name type) ":" id))

(defn- seed-client!
  [fixture-key]
  (let [{:keys [schema objects relationships] :as fixture}
        ((get baseline/fixtures fixture-key))
        conn (datascript/create-conn)
        source-id (str "operator-engine-baseline/" (name fixture-key))]
    (alter-meta! conn assoc :eacl.datascript/source-id source-id)
    (let [client
          (datascript/make-client
           conn
           {:cache cache/no-cache
            :security-key baseline-security-key
            :source-lifecycle "operator-engine-union-baseline"})]
      (eacl/write-schema! client schema)
      (ds/transact!
       conn
       (mapv (fn [index {:keys [id]}]
               {:db/id (- (inc index)) :eacl/id id})
             (range)
             objects))
      (doseq [batch (partition-all 500 relationships)]
        (eacl/create-relationships! client (vec batch)))
      {:client client :fixture fixture})))

(defn- decoded-cursor
  [token]
  (some-> token
          (cursor/token->authenticated-cursor cursor-format-options)
          :cursor))

(defn- page-payload
  [page]
  (let [page-info (:page-info page)
        start-payload (decoded-cursor (:start-cursor page-info))
        end-payload (decoded-cursor (:end-cursor page-info))
        start-common (dissoc start-payload :edge)
        end-common (dissoc end-payload :edge)]
    (when-not (= start-common end-common)
      (throw
       (ex-info
        "Start and end cursors disagree outside their logical edge."
        {:start start-common :end end-common})))
    {:data (mapv object-ref (:data page))
     :page-info (dissoc page-info :start-cursor :end-cursor)
     :cursor-common start-common
     :start-edge (:edge start-payload)
     :end-edge (:edge end-payload)}))

(defn- cursor-fixture
  [fixture-key]
  (let [{:keys [client fixture]} (seed-client! fixture-key)
        principal (get-in fixture [:principals :super-user])
        [_ resource] (first (sort-by key (:reverse-resources fixture)))]
    {:forward
     (page-payload
      (eacl/lookup-resources
       client
       {:subject principal
        :permission (:permission fixture)
        :resource/type (:resource-type fixture)
        :first 2}))
     :reverse
     (page-payload
      (eacl/lookup-subjects
       client
       {:resource resource
        :permission (:permission fixture)
        :subject/type :user
        :first 2}))}))

(defn capture-cursor-payloads
  []
  {:format-version 1
   :base-commit "8dc3b16498788dd822b68e1c4fe25b37a8e8879f"
   :fixtures
   (into (sorted-map)
         (map (fn [fixture-key]
                [fixture-key (cursor-fixture fixture-key)]))
         [:explorer-acyclic :explorer-recursive])})

(defn- read-cursor-snapshot
  []
  (edn/read-string (slurp (repo/file cursor-snapshot-file))))

(defn- read-baseline-index
  []
  (edn/read-string (slurp (repo/file baseline-index-file))))

(defn- remove-basis-local-coordinates
  [snapshot]
  (update snapshot :fixtures
          (fn [fixtures]
            (into
             (sorted-map)
             (map
              (fn [[fixture-key directions]]
                [fixture-key
                 (into
                  {}
                  (map
                   (fn [[direction payload]]
                     [direction
                      (-> payload
                          (update :start-edge dissoc :coords)
                          (update :end-edge dissoc :coords))]))
                  directions)]))
             fixtures))))

(defn- coordinate-shapes
  [snapshot]
  (into
   (sorted-map)
   (for [[fixture-key directions] (:fixtures snapshot)]
     [fixture-key
      (into
       {}
       (for [[direction payload] directions]
         [direction
          (mapv
           (fn [edge-key]
             (when-let [coords (get-in payload [edge-key :coords])]
               {:count (count coords)
                :integers? (every? integer? coords)}))
           [:start-edge :end-edge])]))])))

(defn- sha256-file
  [path]
  (let [digest
        (.digest
         (MessageDigest/getInstance "SHA-256")
         (Files/readAllBytes (.toPath (repo/file path))))]
    (apply str (map #(format "%02x" (bit-and (int %) 255)) digest))))

(deftest exact-union-only-public-baselines-test
  (let [{:keys [digest-domain fixture-digests]}
        (:behavior (read-baseline-index))]
    (doseq [[fixture-key expected-digest] fixture-digests]
      (testing (name fixture-key)
        (is (= expected-digest
               (secure/canonical-digest
                digest-domain
                (baseline/capture-fixture fixture-key))))))))

(deftest decoded-union-only-cursor-semantics-test
  (let [expected (read-cursor-snapshot)
        actual (capture-cursor-payloads)]
    (is (= (remove-basis-local-coordinates expected)
           (remove-basis-local-coordinates actual)))
    (is (= (coordinate-shapes expected)
           (coordinate-shapes actual)))))

(deftest matched-host-performance-and-cursor-artifacts-are-frozen-test
  (let [index (read-baseline-index)]
    (doseq [artifact [(:matched-host-performance index)
                      (:decoded-cursor-payloads index)]]
      (is (= (:sha256 artifact)
             (sha256-file (:path artifact)))
          (:path artifact)))))
