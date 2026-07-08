# ADR 0008: Kotlin developer surface ratchet

Status: Accepted

Date: 2026-07-08

Supersedes ADR 0006 where that record talks about command examples,
repository guidance, or protocol docs that still name removed `kast agent call`
or `kast agent workflow` surfaces as public agent paths. ADR 0007 still owns
the macOS plugin setup authority.

## Decision

Kotlin developer-surface cleanup starts with surfaces a developer or agent can
touch directly, then walks inward to implementation-only contracts:

1. Root CLI and typed `kast agent` commands.
2. Repository managed guidance and packaged skill instructions.
3. Public config keys and IDEA settings persisted by developer workflows.
4. Generated protocol/reference documentation.
5. JSON-RPC catalogs and backend dispatch contracts.
6. Kotlin API/server/backend implementation details.

Every walked path is classified as one of:

| Classification | Rule |
| --- | --- |
| Public | Supported developer or agent surface; must be documented, tested, and reachable through typed commands or config. |
| Internal required | Implementation or generated contract needed by a public surface; document as internal and keep drift-tested. |
| Compatibility | Accepted legacy input only; parse and map to a typed current model, but do not emit as the default. |
| Delete candidate | Unused or stale path that is neither public, internal required, nor compatibility. Remove or fail with replacement guidance. |

## Current Ratchets

- `projectOpen.profile = "jetbrains-plugin"` is the canonical developer
  default for plugin-owned workspace bootstrap.
- `projectOpen.profile = "copilot-lsp"` is compatibility input only and maps
  to the same typed profile kind.
- Generated API reference examples describe internal JSON-RPC methods, not
  public CLI invocations.
- Root managed guidance must direct agents to `kast ready`, `kast repair`, and
  typed `kast agent verify|symbol|diagnostics|impact|rename|lsp` commands.
- `commands.json` remains an internal catalog source for generated artifacts
  and typed command implementations; it is not a public arbitrary dispatch
  surface.

## Source Of Truth

| Surface | Source files |
| --- | --- |
| Typed agent CLI | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/` |
| Managed guidance | `cli-rs/src/install/agent_guidance.rs`, root managed `<kast>` region |
| Packaged skill | `cli-rs/resources/kast-skill/SKILL.md` |
| Public config model | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/` |
| IDEA setup behavior | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/` |
| Protocol docs | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/`, `cli-rs/protocol/` |
| Internal catalog | `cli-rs/resources/kast-skill/references/commands.json`, `.github/scripts/render-rpc-contract-summary.py` |

## Change Rule

Future cleanup must preserve asserted facts as types where Kotlin owns the
boundary. Legacy strings may be accepted at parse boundaries, but behavior must
branch on typed models. If a future change adds a public surface or promotes an
internal catalog path, add a superseding ADR before updating docs or generated
assets.

## Validation

Use focused Gradle tests for Kotlin config, docs, and IDEA behavior:

```console
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.KastConfigTest --tests io.github.amichne.kast.api.docs.AnalysisDocsDocumentTest
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastProjectOpenProfileAutoInitTest --tests io.github.amichne.kast.idea.KastSettingsConfigurableTest
```

When protocol docs or catalog text changes, regenerate and check:

```console
./gradlew :analysis-api:generateDocPages
python3 .github/scripts/render-rpc-contract-summary.py --write
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
```
