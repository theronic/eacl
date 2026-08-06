# EACL-FORMAL-042 — an empty arrow entered the wide-intersection path

The acyclic `can?` arrow optimization special-cased exactly one resource-side
intermediate, but routed an empty stream through the wide-path setup. An empty
arrow can never authorize the query, so production needlessly calculated
direct-grant relations and could open subject-side scans before returning
false.

The first source-shaped Dafny draft exposed the discrepancy because it modeled
zero or one intermediate as requiring no direct-intersection phase. Production
now returns false immediately for an empty resource-side stream. Dafny proves
that the source-shaped result still equals full far-side evaluation and that
zero or one intermediate performs no direct-intersection phase. Generated Java
and JavaScript compare the exact Boolean and logical-work trace with the actual
CLJ/CLJS source across empty, singleton, direct-hit, exhaustive-miss, and
fallback cases.
