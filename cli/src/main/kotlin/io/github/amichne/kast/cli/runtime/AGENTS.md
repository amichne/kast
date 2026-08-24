# CLI runtime process guide

This directory owns typed endpoint admission, lifecycle coordination, and the outer process
effects used to start, observe, and retire one installed indexer runtime.

## Invariants

- Only an admitted runtime endpoint and launch command may reach a process boundary.
- macOS launchd service identity is derived from the exact workspace root, runtime identity, and
  socket; callers never provide service labels.
- Detached process startup propagates only the canonical Java home, canonical user home, and
  deterministic executable path. It never forwards the caller's arbitrary environment.
- Observation, retirement, startup, and environment rejection remain closed typed results.

## Verification ladder

1. Run `./gradlew :cli:test --tests '*RuntimeProcessSessionTest'`.
2. Run `./gradlew :cli:test`.
3. Exercise start, status, and stop through the installed public `kast` command.
