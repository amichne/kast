# Kast task log

- Branch: `feature/remove-stale-build-ceremony`
- Installed build: `kast 0.28.1-206-gbb5bd2145 (IDE-hosted)`
- Installation policy: installed once before implementation; do not reinstall during this task.

## KAST-001 — startup rejects a closed workspace without a useful recovery hint

- Observation: the first `kast start` returned `{"status":"rejected","boundary":"runtime","reason":"ide-descriptor-read-rejected"}` while IntelliJ was running with no project open.
- Resolution: IntelliJ MCP confirmed that no project matched the canonical root. Opening `/Users/amichne/code/kast` in the existing supported IDE and waiting for smart mode made the next `kast start` return `state:"ready"`.
- Issue: the CLI reason does not identify the missing open-project prerequisite or the corrective action.
- Task action: retain the fixed installation and use Kast first, with IntelliJ MCP as fallback.

## KAST-002 — text discovery rejects valid-looking queries without detail

- Observation: text discovery rejected both `kast.vfs-passive-delivery` and `completionReceipt`, and structure discovery rejected `workspace/intellij-read/build.gradle.kts`, with only `reason:"query-rejected"`; exact-name discovery, resolution, and description worked for Kotlin declarations.
- Fallback: IntelliJ MCP text search returned 248 `completionReceipt` matches from the requested included-build source scope.
- Issue: the rejection does not identify which query rule failed or how to correct it.
- Task action: use Kast for supported semantic identity operations and MCP text search for this inventory.

## KAST-003 — a synchronized workspace can transiently lose endpoint readiness

- Observation: after the MCP fallback synchronized four externally changed paths, exact-name discovery returned `reason:"workspace-not-ready"` while IntelliJ reported smart mode and no indexing.
- Resolution: `kast start` immediately returned `state:"ready"`; retrying discovery then found the retained `RepositoryPath` source declaration.
- Issue: the semantic response does not distinguish a restartable endpoint transition from IDE indexing or another workspace blocker.

## KAST-004 — exact discovery mixes workspace source with a cached included-build artifact

- Observation: post-change exact-name discovery for `KastArchitecturePolicy` returned both the current source declaration and a class declaration from Gradle's transformed `build-logic.jar` cache.
- Resolution: the source-backed candidate resolved and described successfully; its qualified identity and file range were used for the final semantic check.
- Issue: an exact workspace query does not distinguish the authoritative source declaration from a derived cached artifact, so callers must filter by source location before asserting uniqueness.
