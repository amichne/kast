# Runtime Module Instructions

This directory owns backend lifecycle, status inspection, descriptor
management, workspace identity, backend selection, and IDEA autolaunch.

Keep lifecycle mutation separate from read-only inspection. Descriptor parsing,
backend selection, workspace identity, and launch side effects must remain in
named part files so callers can see the boundary they depend on.

Semantic workspace admission owns primary, linked, disposable, standalone,
and unsupported workspace classification. IDEA and headless descriptors and
runtime-status responses must match the exact normalized requested root;
shared Git ancestry, branch, or commit is never sufficient authority. An
unprepared root may report non-mutating preparation or headless next actions,
but admission must not copy metadata, launch an IDE, or alter install state.

Ambiguous backend selection returns a typed error with candidate evidence.
