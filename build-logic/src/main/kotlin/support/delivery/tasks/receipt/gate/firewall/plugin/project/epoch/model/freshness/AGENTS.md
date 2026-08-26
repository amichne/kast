# KVP-019 freshness receipt guide

This directory owns the generated VFS-passive freshness report and exact-head KVP-019 receipt
closure. The report binds one IDE-snapshot epoch observation, retained canonical-root and admitted-
epoch evidence, all closed admission cases, all sixteen unavailable constructors and ten
observation-failure stages, zero forbidden work, and exact KVP-017/KVP-018 completion digests.

Re-admit both complete predecessor closures at the same Git head before issuing KVP-019 evidence.
Keep raw JSON and Gradle properties at report or receipt boundaries; expected failures remain
finite typed data until those boundaries render them.

`Kvp019ReportTasks.kt` owns report generation and exact-failure mutation verification so neither
operation becomes a dependency of the KVP-017 predecessor behavior selector.
