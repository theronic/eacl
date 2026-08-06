# EACL-FORMAL-019 — maximum EID was an ordered-merge sentinel

The optimized host specialization initialized the descending merge's
`last-key` to the runtime's maximum integer. If that integer was the first
legitimate value in either sorted stream, the duplicate-suppression branch
treated it as already emitted and silently omitted it.

The Dafny ordered merge uses explicit state and retained the value, so the
failure was a production-source-to-model refinement defect rather than a
theorem defect.

The specialization now represents initialization with a separate
`has-last?` Boolean. The portable regression exercises both a unique and a
duplicated maximum EID in CLJ and CLJS.
