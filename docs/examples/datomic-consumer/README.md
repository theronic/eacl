# Datomic consumer example

This example uses `8.0.0-RC-2026-09-12` from Clojars. It needs the Clojure CLI
and Java 25 or newer. The dependency includes Datomic Peer; no separate
Datomic server, account, source checkout, or formal tooling is needed.

From this directory, start nREPL:

```sh
clojure -Srepro -M:dev:nrepl --port 7894
```

`-Srepro` excludes your user-level dependency configuration. Connect your
editor to port 7894 and evaluate:

```clojure
(require 'consumer :reload)
(consumer/verify!)
;; 1 test, 19 assertions, 0 failures, 0 errors.
```

If you have `clj-nrepl-eval`, you can instead run:

```sh
clj-nrepl-eval -p 7894 "(require 'consumer :reload) (consumer/verify!)"
```

The checks use a fresh memory database and delete it when finished. They cover:

- Application-owned IDs, allowed and denied requests, and resource lookup.
- Application data and relationships committed together for existing entities.
- The public planner's rejection of entities created only in the pending transaction.
- Access before and at an expiration deadline, renewal, and clearing the deadline.
- Role changes and inherited access after a direct share expires.
- A permission check before exposing saved shares.
- Both aggregate query routes supported by this release.
- Atomic deletion of an entity and its relationships.

The test supplies a controlled server clock so it does not need to sleep.
Applications should use EACL's default clock; never take authorization time
from a browser request.

To try the API interactively:

```clojure
(require '[consumer :as example]
         '[eacl.core :as eacl]
         '[datomic.api :as d])
(def system (example/start!))
(def acl (:acl system))
(eacl/create-relationship! acl example/viewer)
(eacl/can? acl example/alice :view example/report) ; true
(eacl/can? acl example/bob :view example/report)   ; false

;; When finished:
(d/delete-database (:uri system))
```

See [atomic writes](../../atomic-writes.md) and [expiration](../../caveats.md)
for the recipes these checks support.
