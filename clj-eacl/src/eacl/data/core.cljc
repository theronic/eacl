(ns eacl.data.core
  (:require-macros [eacl.utils :as utils :refer [spy profile]])
  (:require [datascript.transit :as dt]
            [eacl.data.transit :as bridge-transit]
            [eacl.data.localstorage :as ls]
            [eacl.data.schema :as schema]
            [datascript.core :as d]
            [cljs.pprint :as pprint]
            [taoensso.timbre :as log]))

;; this is showing up in every project, please abstract.

;; todo move to something LS that supports all the web DBs like LocalForge.

(defonce conn (d/create-conn schema/latest-schema)) ;; gross. Mainly here because of set!.

(defonce !db-debug-snapshot (atom nil)) ;;

(def localstorage-key "com.theronic.eacl/DB") ;; pass into config as init plz?

(defn db->string [db]
  ;(log/debug "transit" db)
  (bridge-transit/write-transit-str db))

(defn string->db [s]
  (utils/profile "db deserialization"
                 (bridge-transit/read-transit-str s)))

(def hidden-attrs #{:routing/match :firestore/subscription :firebase/user})

(defn match-hidden-datom [datom]
  (= :routing/match (.-a datom)))
;(get hidden-attrs a))

(defn remove-hidden-datom [_ datom]
  ;(if (= :routing/match (:a datom))
  ;  (log/warn "FOUND!" datom))
  (not= :routing/match (.-a datom)))
;(not (seq (get hidden-attrs (.-a datom)))))
;(log/debug "checking datom" (:a datom) (get hidden-attrs (:a datom))))
;(nil? (get hidden-attrs (:a datom))))

(defn datoms [db & args]
  (apply d/datoms db args))
;(profile [:datoms args] (apply d/datoms db args)))

(defn pull [db selector eid]
  (profile [:pull eid] (d/pull db selector eid)))

(defn entity [db eid]
  (d/entity db eid))
;(profile [:entity eid] (d/entity db eid)))

(defn q [qry & inputs]
  (apply d/q qry inputs))
;(profile qry (apply d/q qry inputs)))

(defn persist!
  "Trust me, it's easier to impl. Transit read/write handlers for weird types than to filter them out."
  [db]
  (try
    (ls/set-item! localstorage-key (db->string db))
    (catch :default ex
      (log/error "Persistence error writing:" ex (pr-str db)))))

;(defn persist! [db]
;  ;(log/debug "persisting DB:" (prn-str db))
;  ;cleaned-db (d/filter db remove-hidden-datom)
;  ;_ (d/filter)
;  ;;datoms' (remove match-hidden-datom datoms)
;  (let [
;        ;db' (d/filter db remove-hidden-datom)
;        datoms (->> (d/datoms db :eavt) (filter #(not= :routing/match (.-a %))))
;        ;db' #datascript/DB
;        ;_      (log/debug "filtered datoms:" datoms)
;        conn1  (d/conn-from-datoms datoms schema/latest-schema) ;; sort is expensive
;        ;_      (log/debug "conn1" @conn1)
;        db'    @conn1]                                      ;; d/datoms db :eavt
;    (try
;      ;conn'   (d/conn-from-datoms datoms' (:schema db))]
;      ;conn'      (d/conn-from-datoms (d/datoms cleaned-db :eavt) (:schema cleaned-db))] ;; this is expensive. Just to fix broken persistence.
;      ;(log/debug "Persisting cleaned db:" db')
;      ;(log/debug "Weird datoms:" (remove (comp not string? :v) (d/datoms db :eavt)))
;      (ls/set-item! localstorage-key (db->string db'))
;      (catch :default ex
;        (log/error "Persistence error writing:" ex (pr-str db'))))))   ;; todo move to database.

(defn tx!
  "Only transacts non-nil."
  [!conn tx-data & args]
  (let [filtered (seq (remove nil? tx-data))]
    (if-not (empty? filtered)
      (apply d/transact! !conn filtered args))))

(defn reset-conn!
  "This is purely a helper for middleware. Not sure if tx-report is called on reset! ?"
  [!conn db]
  (reset! !conn db))
;(render db)
;(persist! db))

(defn ^:export migrate-db-conn!
  [stored-db target-schema]
  (log/warn "Schema changed from" (:schema stored-db) " to " target-schema ". Migrating.")
  (set! conn (d/conn-from-datoms (remove match-hidden-datom (d/datoms stored-db :eavt)) target-schema)))

(defn restore-db! [target-schema]                           ;; pass in key? schema?
  (when-let [stored (ls/get-item localstorage-key)]
    (js/console.log stored)
    (let [stored-db (string->db stored)]                    ;; thread?
      ; upgrade DB how? not=?
      (if (= (:schema stored-db) target-schema)             ;; TODO handle migration path gracefully.
        (reset-conn! conn stored-db)
        (migrate-db-conn! stored-db target-schema))
      stored-db)))

(defn ^:export drop-stored-db []
  (ls/remove-item! localstorage-key))

(defonce !persist-timeout (atom nil))
#?(:cljs
   (defn install-persistence! [!conn]
     ;(drop-stored-db)
     ;; todo: UNLISTEN
     (d/listen! !conn :persistence
                (fn [{:as tx-report :keys [tx-data db-after]}]
                  ;; TODO: debounce persistence.
                  ;(prn "persist!" tx-data)
                  (reset! !db-debug-snapshot db-after)
                  ;; Don't try to use d/filter. Many hours have been wasted. The impl. seems to be broken.
                  ;(let [filtered (filter (fn [datom]
                  ;                         (not (or
                  ;                                (contains? #{:keyboard :window :svg} (:e datom))
                  ;                                (contains? #{:mouse/x :mouse/y :eval/result} (:a datom))))) (seq db-after))]) ;; need a query to match this plz
                  ;(log/debug filtered)
                  (when (seq tx-data)                              ;; eww
                    ;(let [fresh (:db-after (d/with (d/empty-db (:schema db-after)) filtered))])
                    ;(log/debug "fresh" fresh)
                    ;(log/warn "Persisting.")                   ; (count filtered) " datoms.")
                    (when-let [timeout @!persist-timeout]
                      (js/clearTimeout timeout))
                    (reset! !persist-timeout (js/setTimeout #(persist! db-after) 500)))))))