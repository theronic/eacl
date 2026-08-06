# EACL-FORMAL-014 — inherited self-bypass escaped callback accounting

The recursive self-wait guard is dynamically bound. Clojure propagates dynamic
bindings into a child `future`, but that child runs on a different execution
context. Before the fix, the inherited same-key guard invoked the nested
callback directly. The parent callback occupied one coordinator permit while
the child callback ran without acquiring or appearing in the global active
count.

Self-bypass now always calls the context-aware slot wrapper. Same-thread
recursion reuses its existing permit. A child thread sees a different
execution context and acquires another permit before it runs. The shared
flight registry remains unchanged: recursive bypass is not a second
top-level flight.

This is the production distinction Lore's resource analysis requires. A bound
on represented candidates or registered flights is not a bound on actual
callbacks unless every callback path crosses the same execution-capacity
mechanism.
