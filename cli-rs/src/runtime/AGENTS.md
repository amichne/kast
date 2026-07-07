# Runtime Module Instructions

This directory owns backend lifecycle, status inspection, descriptor
management, workspace identity, backend selection, and IDEA autolaunch.

Keep lifecycle mutation separate from read-only inspection. Descriptor parsing,
backend selection, workspace identity, and launch side effects must remain in
named part files so callers can see the boundary they depend on.

Ambiguous backend selection returns a typed error with candidate evidence.
