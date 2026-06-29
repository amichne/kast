# Runtime Module Instructions

This directory owns backend lifecycle, status inspection, RPC passthrough,
descriptor management, workspace identity compatibility, and IDEA autolaunch.

Keep lifecycle mutation separate from read-only inspection. Descriptor parsing,
backend selection, workspace identity, and launch side effects must remain in
named part files so callers can see the boundary they depend on.

Do not silently choose an unsafe backend. If selection is ambiguous, return a
typed error with the candidate evidence.
