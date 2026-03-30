# Analysis server agent guide

`analysis-server` owns the HTTP/JSON transport around `AnalysisBackend`.

## Ownership

Keep this unit focused on the network boundary around the backend interface.

- Keep this module focused on transport concerns: Ktor routing, auth, request
  validation, timeouts, truncation, and descriptor file lifecycle.
- Do not move PSI logic, IntelliJ threading, or standalone CLI parsing into
  this unit. Those belong to the backend hosts.
- Preserve route semantics under `/api/v1`, including `X-Kast-Token` auth,
  capability checks before backend calls, and consistent error mapping.
- Keep descriptor behavior stable. Starting a server writes a descriptor based
  on backend identity and workspace root; closing the server removes it.
- Keep result limiting and pagination metadata aligned with backend responses.
  If truncation changes, update tests and docs together.

## Verification

Prove transport changes with server tests first, then broaden if needed.

- Run `./gradlew :analysis-server:test`.
- If you change transport behavior or descriptor handling, exercise the
  affected host module as well.
