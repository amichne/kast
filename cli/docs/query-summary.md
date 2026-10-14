# Query summaries before contract details

Pi and Copilot query text starts with received counts, original coverage, and blockers.
The overview distinguishes received rows from the invocation's accumulated row count.
Relation counts count connections in received rows, including repeated occurrences.
File counts count received rows with a supplied file location.
Rows without a supplied location remain unknown.
These counts do not describe an unread suffix or prove exhaustive coverage.
The first semantic outcome and `COMPLETE_ONLY` policy remain authoritative.

Exact rows and the existing compact graph remain inline.
Graph tables keep endpoint identities, witness identities, directions, relation kinds, source ranges, and qualifiers.
With available retained authority, default descriptions omit full signature contracts and source bodies.
The issued `READ_RESULT` request with `verbose: true` returns canonical details under that authority.
An expired or stale handle returns its canonical rejection.
The client never retries `RUN` to restore details.

Without retained authority, text keeps all received contracts inline.
An explicit final view or a transport failure also keeps received contracts inline.
Verbose output preserves the canonical result byte for byte.
The output drain keeps its existing byte, page, and time limits.

The focused Node suite uses pinned typed documents and saved native receipts.
It covers received counts, distinct occurrences, signature omission, explicit detail reads, stale rejection, and zero semantic replay.
The new summary measurements occupy `summaryFollowUp` in [query-delivery-baseline.json](query-delivery-baseline.json).
Earlier measurements remain historical records.
Pure replay does not prove live Host behavior, model comprehension, native latency, or token costs.
