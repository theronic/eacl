(ns eacl.datomic.impl-fixed
  "Todo: should contain a fixed index-based implementation of lookup-resources."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl-base :as base]))

; place fixed index-based lookup-resources & count-resources implementation here and update eacl.datomic.impl references.