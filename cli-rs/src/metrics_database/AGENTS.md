# Metrics Database Instructions

This directory contains direct SQLite metrics queries over the source-index
cache that must migrate behind typed backend APIs under
`.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`.
Do not add new direct queries, public results, or consumers here. Until its
follow-on migration lands, changes are limited to correctness fixes required
to preserve the existing surface or to remove it.

Keep query controls and result models separate from SQL execution and helper
serialization. Existing functions return typed direct metrics results or typed
direct metrics errors. New typed metrics contracts belong in `analysis-api` and
are served by the active backend through `analysis-server`.

Presentation belongs in `output` or the calling command.

Impact identity is fail-closed. Verify the compiler anchor, then require a
production declaration row matching FQ name, canonical path, non-null offset,
and kind. The production declaration primary key cannot represent same-FQ
same-file overloads, so functions and properties must return typed overload
granularity degradation rather than aggregate FQ edges. A declaration-row FQ
count may reject additional stored non-callable rows; it is never callable
overload proof.

Impact admits only class, interface, object, function, and property subjects.
Verified parameter or unknown kinds return `UNSUPPORTED_SUBJECT_KIND` before
any declaration, count, or impact-row query; keep zero-SQL regression tests.
