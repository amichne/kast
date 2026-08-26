# Module-role convention guide

This directory owns public `kast.role.*` precompiled convention entry points.

- Keep each role plugin minimal: apply only the JVM capability required by the role and publish the
  exact `kast.moduleRole` identity consumed by architecture admission.
- Child `core/` and `adapter/` owners retain their existing conventions. The direct
  `ide-read-only.gradle.kts` entry represents the bounded terminal IDE-read role.

Verify new role IDs through architecture convention tests and `verifyKastModuleGraph`.
