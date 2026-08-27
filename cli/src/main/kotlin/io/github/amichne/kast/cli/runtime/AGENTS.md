# CLI runtime process guide

This directory owns typed IDE descriptor admission, lifecycle coordination, and the outer process
effects used by the still-explicit legacy indexer runtime.

## Invariants

- IDE descriptor admission reads one deterministic exact-root location and preserves compatibility,
  root, socket, process, and reachability proof in `AdmittedIdeEndpoint`.
- Descriptor admission never scans sockets, selects a first match, or returns raw descriptor text.
- `IdeOnlyRuntimeDemander` is the installed root-to-endpoint transition. It may issue transport
  authority only from `AdmittedIdeEndpoint`; every descriptor failure remains a closed rejection.
- The IDE-hosted lifecycle may observe the already-admitted endpoint but cannot terminate the IDE,
  delete endpoint state, start a process, or recover through the legacy runtime.
- Only an admitted runtime endpoint and launch command may reach a process boundary.
- macOS launchd service identity is derived from the exact workspace root, runtime identity, and
  socket; callers never provide service labels.
- Detached process startup propagates only the canonical Java home, canonical user home, and
  deterministic executable path. It never forwards the caller's arbitrary environment.
- Observation, retirement, startup, and environment rejection remain closed typed results.

## Verification ladder

1. Run `./gradlew :cli:test --tests '*IdeEndpointAdmission*Test'` and
   `./gradlew :cli:test --tests '*RuntimeProcessSessionTest'`.
2. Run `./gradlew :cli:test`.
3. Exercise start, status, and stop through the installed public `kast` command.
