# Symbol Discovery And Context-Aware Resolve

## Summary
Add a new agent-facing `skill/discover-symbol` command and native `kast_symbol_discovery` tool that turns a simple name plus optional context into ranked candidates. Extend both `symbol/resolve` and `skill/resolve` so agents can request bounded declaration, documentation, sibling-member, and nearby-line context in one call.

## Key Changes
- Add skill-layer DTOs in `analysis-api` for `KastSymbolDiscoveryRequest/Query/Response`.
  - Request: `symbol`, optional `filePath`, `line`, `codeSnippet`, `kind`, `maxResults`.
  - Response candidates: `symbol`, `confidence: Int` from 0-100, `rankingSignals: List<String>`, `disambiguation: KastResolveQuery`, `contextSnippets`, and `nextSteps`.
  - Context snippets are declaration-site and context-match snippets in v1, not per-candidate reference searches, to preserve workspace-symbol-like latency.
- Implement discovery in `SkillRpcOrchestrator`.
  - Call `backend.workspaceSymbolSearch` once with the simple name and bounded `maxResults`.
  - Rank exact simple-name matches first, then context-file proximity, line proximity, snippet overlap, kind match, and containing declaration match.
  - Generate disambiguation params from existing symbol metadata: `symbol`, `kind`, `fileHint`, and `containingType` when available.
- Extend context-aware resolve.
  - Add missing raw `SymbolQuery` fields: `includeSurroundingMembers: Boolean = false` and `surroundingLines: Int = 0`.
  - Add the same plus existing `includeDeclarationScope` and `includeDocumentation` to `KastResolveRequest/Query`.
  - Extend `SymbolResult` or `Symbol` with structured optional context: `surroundingMembers: List<Symbol>` and `surroundingLines: SourceSnippet?`.
  - Reuse existing `DeclarationScope` for full declaration text when `includeDeclarationScope=true`.
- Keep bounds server-owned.
  - `surroundingLines` is validated non-negative and capped by server/default constants.
  - Declaration text, member lists, and snippets are truncated by conservative fixed caps with explicit truncation metadata where applicable.
- Update all contract surfaces together.
  - Add dispatcher route `skill/discover-symbol`.
  - Add command spec entry and regenerate `.agents/skills/kast/references/commands.json`.
  - Add native tool schema in `.github/extensions/kast/extension.mjs`.
  - Regenerate `docs/reference/*` and `docs/openapi.yaml` for raw JSON-RPC schema changes.
  - Update agent-facing quickstart/reference wording so discovery becomes the recommended first step for ambiguous names.

## Test Plan
- Add shared contract tests for:
  - Discovery returns multiple same-name candidates with confidence, ranking signals, disambiguation params, context snippets, and next steps.
  - File/line/snippet context changes ranking deterministically.
  - Discovery uses one backend workspace-symbol search path, not per-candidate resolve/reference fanout.
  - Resolve with each context flag populates only the requested optional fields.
  - `surroundingLines` validation rejects negative values and caps excessive values.
- Add backend tests in standalone and IntelliJ paths for declaration scope, KDoc, sibling members, and surrounding-line snippets.
- Add skill/dispatcher tests for `skill/discover-symbol` and context-rich `skill/resolve`.
- Run/regenerate:
  - `./gradlew :analysis-api:generateDocPages`
  - `./gradlew :kast-cli:generateVersionedCommandSpec`
  - Narrow tests first: `:analysis-api:test`, `:analysis-server:test`, `:shared-testing:test`, `:backend-standalone:test`, `:backend-intellij:test`, `:kast-cli:test`
  - Broaden to packaged/native validation if command spec or extension packaging tests fail.

## Assumptions
- Discovery is a skill/native-tool feature, not a new backend capability.
- Context-aware resolve is implemented on both raw position-based `symbol/resolve` and agent-facing name-based `skill/resolve`.
- Confidence uses `Int` 0-100 plus `rankingSignals`.
- Server defaults own response-size caps; callers only choose booleans and `surroundingLines`.
