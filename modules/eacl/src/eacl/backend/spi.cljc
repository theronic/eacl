(ns eacl.backend.spi)

(defn cache-stamp [backend]
  ((:cache-stamp backend)))

(defn relation-defs [backend resource-type relation-name]
  ((:relation-defs backend) resource-type relation-name))

(defn permission-defs [backend resource-type permission-name]
  ((:permission-defs backend) resource-type permission-name))

(defn subject->resources [backend subject-type subject-id relation-id resource-type cursor-resource-id]
  ((:subject->resources backend) subject-type subject-id relation-id resource-type cursor-resource-id))

(defn resource->subjects [backend resource-type resource-id relation-id subject-type cursor-subject-id]
  ((:resource->subjects backend) resource-type resource-id relation-id subject-type cursor-subject-id))

(defn direct-match? [backend subject-type subject-id relation-id resource-type resource-id]
  ((:direct-match? backend) subject-type subject-id relation-id resource-type resource-id))
