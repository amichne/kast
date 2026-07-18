# Runtime Module Instructions

This directory owns backend lifecycle, status inspection, descriptor
management, workspace identity, backend selection, and IDEA autolaunch.

`lease.rs` owns the coordinator-safe exact-root agent lease. Its authenticated
record binds workspace classification, backend descriptor and process-start
identity, effective install generation, caller session, and started-versus-
borrowed disposition. It may compose the existing lifecycle and readiness
authorities but must not become another runtime manager. Release may stop only
the still-matching headless runtime recorded as started; IDEA and borrowed
resources are preserved.

Keep lifecycle mutation separate from read-only inspection. Descriptor parsing,
backend selection, workspace identity, and launch side effects must remain in
named part files so callers can see the boundary they depend on.

Semantic workspace admission owns primary, linked, disposable, standalone,
and unsupported workspace classification. IDEA and headless descriptors and
runtime-status responses must match the exact normalized requested root;
shared Git ancestry, branch, or commit is never sufficient authority. An
unprepared root may report non-mutating preparation or headless next actions,
but admission must not copy metadata, launch an IDE, or alter install state.
Verification is reuse-only and must not start IDEA or headless runtimes, prune
dead descriptors, or rewrite descriptor registry state. Thread an explicit
preserve/prune policy through inspection; lifecycle owners may prune, while
read-only status, admission, and verification preserve.

The unprepared headless route is read-only. Applied public mutations on macOS
require valid exact-root plugin preparation regardless of backend selection;
enforce this before descriptor discovery or opening a socket. A descriptor
cannot make a non-Gradle root supported, and a temporary clone is not primary
merely because it owns a `.git` directory.

Automatic selection with more than one ready exact-root backend returns a
typed error with candidate evidence. A sole ready backend wins over the host
fallback. Explicit backend selection remains authoritative.
