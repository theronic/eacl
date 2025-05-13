(ns eacl.spicedb.consistency)

; EACL is always fully-consistent, so these
; are just here to maintain API-level compatibility.
; Impl. should throw if no fully-consistent is passed.
(defn fresh [token] :fresh)
(def minimize-latency :minimize-latency)
(def fully-consistent :fully-consistent)