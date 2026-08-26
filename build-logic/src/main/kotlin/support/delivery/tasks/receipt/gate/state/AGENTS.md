# Delivery-state receipt guide

This directory owns KVP-008's generated delivery-state report and exact-head receipt progression.

- The report is a generated-serializer document derived from empty, partial, stale, duplicate, and
  complete receipt closures. Decoding independently re-derives and compares the complete document.
- Receipt tasks execute fixed KVP-008 test selectors, directly re-admit KVP-007 completion, bind
  the report digest, and derive KVP-008 completion at one exact Git head.
- Registration replaces only the generic KVP-008 placeholders.

Do not add a writable completion or PASS field. Terminal and requirement evidence must remain a
projection of admitted receipts through `DerivedProgramState`.
