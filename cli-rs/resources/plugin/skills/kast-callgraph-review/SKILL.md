---
name: kast-callgraph-review
description: Use to inspect incoming and outgoing Kotlin call hierarchy through Kast LSP before changing or reviewing behavior.
---

# Kast Callgraph Review

1. Resolve the symbol exactly through LSP or Kast RPC.
2. Fetch incoming and outgoing call hierarchy with bounded depth.
3. Summarize callers, callees, truncation, and unresolved edges.
4. Mark stale, ambiguous, or partial call facts as blockers.
5. Use the callgraph to choose focused validation.
