(ns eacl.data.transit
  (:require-macros [eacl.utils :refer [spy profile]])
  (:require [cognitect.transit :as transit]
            [datascript.core :as d]
            [datascript.db :as db]
            [me.tonsky.persistent-sorted-set :as pss]
            [cognitect.transit :as t]
            [reitit.core]
            [taoensso.timbre :as log])
  (:import [goog.date UtcDateTime]))

;; todo update ignored persistence attrs.

(def read-handlers
  {"m"                 (transit/read-handler (fn [s] (UtcDateTime.fromTimestamp s)))
   "u"                 uuid                                 ;; favor ClojureScript UUIDs instead of Transit UUIDs
   "reitit.core/Match" identity                             ;; hmm
   ;; https://github.com/cognitect/transit-cljs/pull/10
   "datascript/DB"     db/db-from-reader
   "datascript/Datom"  db/datom-from-reader})

(def write-handlers
  {UtcDateTime              (transit/write-handler
                              (constantly "m")
                              (fn [v] (.getTime v))
                              (fn [v] (str (.getTime v))))
   reitit.core/Match        (t/write-handler (constantly "reitit.core/Match")
                                             (fn [match]    ;; Beware, this does not respect d/filter.
                                               ;(js/console.log "db type: " db)
                                               {:data        (:data match)
                                                :path-params (:path-params match)}))
   reitit.core/PartialMatch (t/write-handler (constantly "reitit.core/Match")
                                             (fn [match]    ;; Beware, this does not respect d/filter.
                                               ;(js/console.log "db type: " db)
                                               {:data        (:data match)
                                                :path-params (:path-params match)}))
   ;{:template (:template match)
   ; :data (:data  result path-params path)}
   ;(pr-str match)
   ;{:schema (:schema db)
   ; :datoms (:eavt db)}))
   db/DB                    (t/write-handler (constantly "datascript/DB")
                                             (fn [db]       ;; Beware, this does not respect d/filter.
                                               ;(js/console.log "db type: " db)
                                               {:schema (:schema db)
                                                :datoms (:eavt db)})) ;; was (:eavt db). tried (seq db)
   db/Datom                 (t/write-handler (constantly "datascript/Datom")
                                             (fn [d]
                                               ;(log/debug "transiting" d)
                                               (if (or
                                                     (contains? #{:keyboard :window :svg} (.-e d))
                                                     (contains? #{:mouse/x :mouse/y :eval/result} (.-a d)))
                                                 [0 :broken/attr 0 (db/datom-tx d)]
                                                 (if (db/datom-added d)
                                                   [(.-e d) (.-a d) (.-v d) (db/datom-tx d)]
                                                   [(.-e d) (.-a d) (.-v d) (db/datom-tx d) false]))))
   pss/BTSet                (t/ListHandler.)})


(defn read-transit-str [s]
  (t/read (t/reader :json {:handlers read-handlers}) s))

(defn write-transit-str [o]
  (t/write (t/writer :json {:handlers write-handlers}) o))
