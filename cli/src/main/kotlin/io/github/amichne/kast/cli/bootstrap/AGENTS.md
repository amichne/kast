# Installed CLI bootstrap guide

This directory owns the sole service-loaded installed `kast` composition and its local control
metadata boundary.

## Local scope

- `InstalledKastCliComposition` must construct `IdeOnlyRuntimeDemander` from the installed protocol
  digests, supported host tuple, the hosted publisher's stable `/tmp` endpoint directory, and
  the compatibility-derived opaque hosted identity. Product version comes from the installed CLI
  jar manifest; no runtime manifest is an installed resource. Never substitute the launcher JVM's
  platform-specific temp directory.
- Managed runtime acquisition, runtime stores, archives, indexer executables, launchd, and process
  start must remain unreachable from this composition. Prove this with both
  `verifyNoDefaultRuntimeFallbackNegative` and `verifyNoDefaultRuntimeFallback`.
- Raw installation paths and resource text may leave only at `InstalledKastControlProduct`.
