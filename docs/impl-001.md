## Implementation Plan

### Phase 1: Prove the architecture (first pass)

Build order, each step validates the previous:

1. **`:analysis-api`** — Define `AnalysisBackend`, all model types, capability enums. Pure Kotlin, compiles in seconds, zero external deps. This is the contract everything else depends on.

2. **`:shared-testing`** — `FakeAnalysisBackend` (in-memory, returns canned results) + contract test suite that asserts response shapes. These tests run against *any* `AnalysisBackend` implementation — they're how you verify both real backends conform.

3. **`:analysis-server`** — Ktor routes wired to a `FakeAnalysisBackend`. Verify the HTTP layer works end-to-end with curl/httpie. This is deployable and testable before either real backend exists.

4. **`:backend-intellij`** — Implement `resolveSymbol` and `findReferences` first (these prove PSI access works through the server). Then `rename` (proves mutation path). Then `callHierarchy` and `diagnostics`.

5. **`:backend-standalone`** — Implement `resolveSymbol` and `diagnostics` first (these prove the Analysis API session initializes and resolves correctly). Then `findReferences` and `rename`. `callHierarchy` last (requires the most AA surface area).

### Phase 2: Harden

- File content hashing to detect stale edits
- `ReadAction.nonBlocking` + cancellation for long-running IntelliJ queries
- Standalone backend: warm index cache on startup, incremental re-index on file change notification
- Schema version enforcement (server rejects clients with mismatched `schema_version`)

### Phase 3: Expand

- Additional mutations: `extractFunction`, `inlineVariable`, `safeDelete` (IntelliJ-only initially)
- Workspace-wide operations: `workspaceSymbols` (fuzzy symbol search)
- Event stream: SSE endpoint for push-based diagnostics (file-save triggered)
