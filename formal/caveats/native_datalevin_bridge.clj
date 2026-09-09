(ns eacl.formal.caveats.native-datalevin-bridge
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.qualifiers :as qualifiers]
            [eacl.formal.caveats.native-bridge :as bridge]))

(deftest generated-native-lifecycle
  (let [dir (util/tmp-dir (str "qualifier-model-" (random-uuid)))
        conn (schema/create-conn dir {:app/seen {:db/valueType :db.type/long :db/cardinality :db.cardinality/many}})]
    (try
      (let [token (:write-token (schema/ensure-physical-schema! conn))]
        (schema/write-schema! conn bridge/schema-source {} (schema/current-schema-generation (d/db conn)) token)
        (bridge/run-lifecycle! (qualifiers/writer conn) d/entid 904))
      (finally (d/close conn) (util/delete-files dir)))))
