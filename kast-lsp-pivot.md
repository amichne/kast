# Kast Copilot LSP Pivot — Phase Requirements and Audit Plan

## Purpose

This document defines the first requirements set for pivoting Kast away from the unreliable Copilot SDK path and toward a standard Language Server Protocol integration consumed by GitHub Copilot CLI.

The goal is not to make Copilot “smarter” through prompt volume. The goal is to make Kast the authoritative code-intelligence substrate and expose that substrate through stable protocol surfaces, narrowly scoped instructions, hooks, skills, and plugin packaging.

## Strategic Position

- Current direction:
    - The Copilot SDK integration path is considered non-operable for daily-driver use.
    - The SDK path should not remain the primary integration point.
    - Kast should stop depending on bespoke Copilot wrapper behavior as the trusted path.
    - Copilot should become a consumer of Kast facts, not the owner of Kast orchestration.

- Target direction:
    - Kast exposes compiler-grounded Kotlin code intelligence through `kast lsp --stdio`.
    - Copilot CLI consumes Kast through standard LSP configuration.
    - Instructions remain minimal.
    - Skills provide just-in-time workflow guidance.
    - Hooks enforce hard behavioral boundaries.
    - Write-capable operations are introduced only after read-only navigation and hook enforcement are proven.
    - Plugin packaging becomes the distribution mechanism after the local integration is stable.

## Non-Goals

- Do not build a new Copilot SDK adapter.
- Do not depend on prompt instructions as the primary safety mechanism.
- Do not expose broad file dumps as the default data path.
- Do not implement every LSP capability before proving the useful subset.
- Do not enable rename or workspace edits before exact symbol resolution, reference enumeration, and validation hooks exist.
- Do not allow the old SDK path to remain a hidden default fallback.

## Global Requirements

- Kast must be the source of truth for Kotlin symbol, reference, type, implementation, call graph, and diagnostic facts.
- The LSP server must fail closed when the index is stale, ambiguous, missing, or inconsistent.
- All model-facing results must be compact, structured, and location-oriented.
- Broad textual search should become a fallback path, not the primary exploration path.
- Guardrails must be enforced mechanically where possible.
- Every phase must produce observable evidence that can be reviewed independently.
- Each phase must be shippable without requiring later phases to be complete.
- The old Copilot SDK integration must not be required for any phase to function.

---

# Phase 1 — Read-Only Kast LSP

## Where We Are

- Kast already has compiler-grounded primitives or adjacent capabilities for:
    - Symbol discovery.
    - Symbol resolution.
    - References.
    - Callers/call hierarchy.
    - Type hierarchy or implementation relationships.
    - Diagnostics.
    - File outline.
    - Workspace symbol search.
    - Compiler-backed database facts.

- These capabilities exist as Kast-native command/RPC/database surfaces, not as a standard language-server interface.
- Copilot currently lacks a reliable path to consume these facts without fragile bespoke wrappers or excessive context injection.
- The agent can still fall back to grep, file reads, and probabilistic inference even when compiler facts would be more precise.

## What We Need

- Add a new LSP server entry point:

```bash
kast lsp --stdio
```

- Implement the LSP lifecycle:
    - `initialize`
    - `initialized`
    - `shutdown`
    - `exit`

- Implement text document synchronization sufficient for Kotlin files:
    - `textDocument/didOpen`
    - `textDocument/didChange`
    - `textDocument/didClose`

- Implement read-only language intelligence:
    - `textDocument/definition`
    - `textDocument/references`
    - `textDocument/hover`
    - `textDocument/documentSymbol`
    - `workspace/symbol`
    - `textDocument/implementation`
    - `textDocument/prepareCallHierarchy`
    - `callHierarchy/incomingCalls`
    - `callHierarchy/outgoingCalls`

- Add internal adapters from LSP requests to Kast facts:
    - URI to workspace-relative path conversion.
    - LSP position to Kotlin compiler offset conversion.
    - Kotlin compiler offset to LSP range conversion.
    - Unsaved buffer overlay support.
    - Workspace root detection.
    - Module/source-set detection.
    - Stale index detection.
    - Ambiguous symbol handling.
    - Timeout and cancellation handling.

- Ensure every response is bounded:
    - Locations, ranges, symbol names, signatures, and compact metadata are acceptable.
    - Full file contents, huge reference dumps, and unbounded graph expansions are not acceptable.

- Add tests for:
    - Lifecycle protocol.
    - UTF-16 position mapping.
    - Definition lookup.
    - Reference lookup.
    - Hover content.
    - Document symbols.
    - Workspace symbols.
    - Implementation lookup.
    - Incoming and outgoing call hierarchy.
    - Stale-index failure.
    - Ambiguous symbol failure.

## How We Will Know It Is Complete

- `kast lsp --stdio` starts from the repository root without manual daemon setup.
- The server responds correctly to `initialize` and advertises only the capabilities it actually supports.
- A deterministic fixture project proves all read-only methods return expected locations.
- UTF-16 position handling is tested with non-ASCII source text.
- Unsaved buffer behavior is either supported or explicitly rejected with a clear diagnostic.
- Stale index state produces a structured failure instead of stale or invented answers.
- Ambiguous symbol resolution produces an explicit ambiguity result.
- No write-capable LSP operations are advertised in this phase.
- Copilot SDK is not required for any read-only LSP behavior.
- The read-only LSP server can be exercised by a generic LSP client test harness.

## Why It Is Necessary

- This phase creates the stable substrate.
- It moves Kast from a custom agent integration into a standard language-intelligence surface.
- It gives Copilot compact, structured, compiler-backed facts instead of encouraging broad text reads.
- It reduces context pollution because the model receives locations and symbol metadata rather than entire files.
- It creates a safe base for later write operations.
- It makes the integration testable outside Copilot, which is critical for debugging and auditability.

## Phase 1 Audit Questions

- Does the LSP server work with a generic LSP client, or only with Copilot?
- Are all advertised capabilities actually implemented?
- Are stale and ambiguous states represented explicitly?
- Are position/range mappings tested against Kotlin source with Unicode?
- Does the implementation avoid leaking full file contents by default?
- Can read-only behavior be validated without the Copilot SDK?

---

# Phase 2 — Copilot CLI Wiring

## Where We Are

- Kast can expose read-only LSP behavior after Phase 1.
- Copilot CLI needs explicit repository-level configuration to discover and launch Kast as a language server.
- Without repository configuration, developers must manually remember setup steps, which weakens adoption and repeatability.

## What We Need

- Add repository-local LSP configuration:

```json
{
  "lspServers": {
    "kast-kotlin": {
      "command": "kast",
      "args": ["lsp", "--stdio"],
      "fileExtensions": {
        ".kt": "kotlin",
        ".kts": "kotlin"
      },
      "rootUri": ".",
      "requestTimeoutMs": 90000,
      "initializationOptions": {
        "indexMode": "compiler-backed",
        "failOnStaleIndex": true,
        "preferCompilerFactsOverTextSearch": true
      }
    }
  }
}
```

- Place the configuration at:

```text
.github/lsp.json
```

- Add minimal repository instructions:

```text
.github/copilot-instructions.md
```

- The instructions should say:
    - Use Kast/LSP for Kotlin symbol navigation before grep.
    - Prefer definition, references, hover, workspace symbols, document symbols, implementations, and call hierarchy over raw file search.
    - Do not perform Kotlin refactors until the symbol has been resolved and references have been enumerated.
    - Treat stale or ambiguous Kast results as blockers, not as permission to guess.

- Add optional path-specific instructions only if they are non-overlapping:
    - `.github/instructions/kotlin.instructions.md`
    - `.github/instructions/gradle.instructions.md`
    - `.github/instructions/tests.instructions.md`

- Add a smoke-test script:
    - Starts Copilot CLI.
    - Runs `/lsp`.
    - Runs `/lsp test kast-kotlin`.
    - Exercises at least one definition/reference prompt against a known fixture.

- Add transcript-based evidence capture:
    - Record whether Copilot used LSP.
    - Record whether it avoided broad grep/file reads.
    - Record whether it returned compiler-backed locations.

## How We Will Know It Is Complete

- `/lsp` shows `kast-kotlin` as configured.
- `/lsp test kast-kotlin` passes.
- Copilot CLI can answer “where is this symbol defined?” using LSP.
- Copilot CLI can answer “where is this symbol referenced?” using LSP.
- The model does not need to read a large set of files to answer basic symbol-navigation questions.
- Repository instructions fit on roughly one screen.
- There are no conflicting instruction files.
- The old Copilot SDK path is not needed for Copilot to access Kast read-only intelligence.

## Why It Is Necessary

- This phase proves that the standard LSP path is viable as the replacement integration.
- It moves the integration from “Kast can technically answer questions” to “Copilot can consume Kast in daily use.”
- It provides visible proof that compiler-backed facts reduce text-search behavior.
- It gives reviewers a concrete CLI-level acceptance criterion rather than a subjective impression.
- It keeps instructions small, preventing the new approach from degenerating into prompt sprawl.

## Phase 2 Audit Questions

- Does Copilot discover the Kast LSP through repository configuration?
- Does `/lsp test kast-kotlin` pass reliably?
- Are the instructions minimal and non-conflicting?
- Does transcript evidence show reduced grep/file-dump behavior?
- Are failures clear enough for a developer to fix setup problems?
- Is any Copilot SDK code still required for normal use?

---

# Phase 3 — Hook Integration

## Where We Are

- Copilot can consume read-only Kast facts after Phases 1 and 2.
- However, the model can still choose unsafe paths:
    - Broad grep over source roots.
    - Large file dumps.
    - Edits without symbol resolution.
    - Edits to generated files.
    - Public API changes without explicit intent.
    - Gradle/build-logic changes without explicit scope.
    - Premature “done” states without diagnostics.

- Instructions alone are not sufficient guardrails.

## What We Need

- Add repository hook configuration:

```text
.github/hooks.json
```

- Add hook scripts:

```text
.github/hooks/kast-pre-tool-use.sh
.github/hooks/kast-post-tool-use.sh
.github/hooks/kast-agent-stop.sh
```

- Implement `preToolUse` policy:
    - Block broad `grep` over Kotlin source roots when a symbol-aware path is available.
    - Block broad `find` or recursive file enumeration over the repository root.
    - Block full-file reads above a configured size threshold unless explicitly allowed.
    - Block edits to generated files.
    - Block edits to public API surfaces unless the user explicitly requested API change.
    - Block Kotlin rename-like edits unless the target symbol has been resolved.
    - Block dangerous shell commands unless explicitly approved.

- Make pre-tool denials corrective:
    - The denial message should tell the agent which Kast/LSP operation to use instead.

- Implement `postToolUse` policy:
    - After edits, run narrow Kast validation.
    - Attach concise diagnostics back to the model.
    - Summarize changed files.
    - Identify whether changed files are generated, public API, test-only, or production code.
    - Avoid injecting large raw output into context.

- Implement `agentStop` policy:
    - Block final completion if required validation has not run.
    - Block final completion if diagnostics are newly failing and unreported.
    - Block final completion if edits occurred without a changed-file summary.
    - Permit completion only when validation status is explicit.

- Add configuration options:
    - Maximum readable file size before hook warning/block.
    - Generated file path patterns.
    - Public API path patterns.
    - Allowed build/test commands.
    - Emergency override mechanism requiring explicit user intent.

- Add tests for:
    - Broad grep blocked.
    - Safe targeted command allowed.
    - Generated file edit blocked.
    - Public API edit blocked without explicit request.
    - Post-edit diagnostics injected compactly.
    - Agent stop blocked when validation is missing.
    - Hook failure behavior is safe.

## How We Will Know It Is Complete

- Unsafe broad grep is denied with a message directing the agent to Kast/LSP.
- Large file dumps are denied or summarized.
- Generated-file edits are denied.
- Public API edits require explicit user intent.
- Kotlin refactor-like edits require prior symbol resolution.
- Post-edit validation runs automatically.
- The final response cannot claim completion when validation is missing.
- Hook output remains compact and does not pollute context.
- Hook tests pass locally and in CI.
- The hooks are usable without the Copilot SDK.

## Why It Is Necessary

- This phase converts guidance into enforcement.
- It reduces reliance on the model choosing the right behavior.
- It protects against context pollution by blocking large or low-signal tool outputs.
- It prevents unsafe edits before write-capable LSP operations are introduced.
- It creates mechanical evidence that the integration is safer than prompt-only Copilot usage.
- It gives enterprise reviewers a concrete policy surface to inspect.

## Phase 3 Audit Questions

- Are unsafe behaviors mechanically blocked, or merely discouraged?
- Do blocked actions provide useful alternate paths?
- Are hook outputs bounded?
- Can hooks fail safely?
- Does post-edit validation run automatically?
- Can the agent falsely claim completion after an unvalidated edit?
- Is there a controlled override path for advanced users?

---

# Phase 4 — Write-Capable Kast LSP

## Where We Are

- Read-only LSP facts are available.
- Copilot can consume Kast through `.github/lsp.json`.
- Hooks can block unsafe behavior and enforce validation.
- The system is ready to expose controlled write operations.

## What We Need

- Add write-capable LSP methods:
    - `textDocument/prepareRename`
    - `textDocument/rename`
    - Optional later: `textDocument/codeAction`

- `prepareRename` must:
    - Resolve the symbol exactly.
    - Reject ambiguous symbols.
    - Reject symbols from stale indexes.
    - Reject generated code unless explicitly allowed.
    - Reject unsupported symbol kinds.
    - Return a precise range and placeholder only when safe.

- `rename` must:
    - Require a successful prepare path.
    - Enumerate all known references.
    - Generate a bounded `WorkspaceEdit`.
    - Include only files within the trusted workspace.
    - Avoid raw textual substitution.
    - Refuse if the reference set is incomplete or stale.
    - Refuse if the proposed new name is invalid.
    - Refuse if edits would cross a blocked policy boundary.

- Add edit preview behavior:
    - Changed files.
    - Number of edits.
    - Symbol identity.
    - Reference count.
    - Excluded or unresolved references.
    - Validation commands to run after apply.

- Integrate with hooks:
    - `preToolUse` blocks non-LSP rename-like edits.
    - `postToolUse` runs diagnostics after rename.
    - `agentStop` blocks completion if validation has not run.

- Add diagnostics support if not already present:
    - Publish or expose diagnostics for changed files.
    - Distinguish pre-existing diagnostics from newly introduced diagnostics.
    - Summarize diagnostic deltas compactly.

- Add tests for:
    - Successful local rename.
    - Successful cross-file rename.
    - Ambiguous rename rejection.
    - Generated-code rename rejection.
    - Stale-index rename rejection.
    - Invalid new-name rejection.
    - Partial-reference-set rejection.
    - Workspace boundary rejection.
    - Diagnostic delta reporting.
    - Hook enforcement around rename.

## How We Will Know It Is Complete

- `prepareRename` returns success only for safe, exact rename targets.
- `prepareRename` rejects ambiguity and stale facts.
- `rename` returns a correct `WorkspaceEdit`.
- The edit set updates all known references in fixture projects.
- The edit set does not modify generated or out-of-workspace files.
- Post-rename diagnostics run automatically.
- New diagnostics are reported clearly.
- Copilot cannot bypass LSP rename with naive search-and-replace without hook denial.
- The old SDK path is not involved in rename.
- Rename behavior is reproducible under tests.

## Why It Is Necessary

- Rename is the first high-value write operation because it directly demonstrates compiler-grounded safety.
- It is also the highest-risk early operation because naive search-and-replace can corrupt code.
- A safe rename implementation proves that Kast is not merely a retrieval aid; it can constrain mutation.
- Write-capable LSP creates a credible bridge from code understanding to code transformation.
- This phase should not happen before hooks because the enforcement layer is what prevents unsafe bypass.

## Phase 4 Audit Questions

- Does rename depend on compiler-backed references rather than text matching?
- Does prepare fail when safety cannot be proven?
- Are all edits represented as a workspace edit?
- Are generated files and workspace boundaries protected?
- Does validation distinguish pre-existing failures from new failures?
- Can Copilot bypass this with ordinary edit tools?
- Are rename failures explicit and actionable?

---

# Phase 5 — Plugin Packaging

## Where We Are

- Local repository wiring works.
- Hooks exist.
- Read-only and write-capable LSP behavior exists.
- Instructions and skills can be tested locally.
- The remaining problem is repeatable distribution.

## What We Need

- Create a Kast Copilot plugin package containing:
    - LSP configuration.
    - Hooks.
    - Skills.
    - Custom agents.
    - Minimal instructions.
    - Optional MCP configuration only if needed for non-LSP enterprise metadata.

- Suggested plugin layout:

```text
kast-copilot-plugin/
  plugin.json
  lsp.json
  hooks.json
  hooks/
    kast-pre-tool-use.sh
    kast-post-tool-use.sh
    kast-agent-stop.sh
  agents/
    kast-explorer.agent.md
    kast-refactorer.agent.md
    kast-reviewer.agent.md
  skills/
    kast-symbol-investigation/
      SKILL.md
    kast-safe-rename/
      SKILL.md
    kast-callgraph-review/
      SKILL.md
    kast-api-surface-change/
      SKILL.md
  instructions/
    kast-kotlin.md
```

- Define custom agents:
    - `kast-explorer`
        - Read-only.
        - Symbol navigation.
        - No edits.
    - `kast-refactorer`
        - Edits allowed only after symbol resolution.
        - Uses LSP rename when available.
        - Requires validation.
    - `kast-reviewer`
        - Reviews changed files.
        - Runs diagnostics.
        - Identifies API/test/build impact.
    - `kast-migration-planner`
        - Planning only.
        - No edits.

- Define skills:
    - `kast-symbol-investigation`
        - Resolve symbol.
        - Fetch definition.
        - Fetch references.
        - Fetch call hierarchy.
        - Summarize findings.
    - `kast-safe-rename`
        - Resolve exact symbol.
        - Prepare rename.
        - Preview workspace edit.
        - Apply rename.
        - Run diagnostics.
    - `kast-callgraph-review`
        - Identify incoming/outgoing calls.
        - Highlight risky callers.
        - Summarize impact.
    - `kast-api-surface-change`
        - Detect public API edits.
        - Require explicit intent.
        - Run focused validation.

- Add plugin validation:
    - Plugin installs from local path.
    - Plugin exposes LSP config.
    - Plugin exposes hooks.
    - Plugin exposes agents.
    - Plugin exposes skills.
    - Plugin does not require copying repo-local config manually.
    - Plugin version is visible.
    - Plugin can be uninstalled cleanly.

- Add documentation:
    - Installation.
    - Verification.
    - Required Kast version.
    - Troubleshooting.
    - Known limitations.
    - Enterprise policy notes.
    - Decommissioned SDK path note.

## How We Will Know It Is Complete

- A fresh repository can install the plugin and get Kast LSP behavior without manually copying configuration.
- `/lsp` shows the plugin-provided Kast server.
- `/lsp test kast-kotlin` passes.
- Hooks fire from the plugin.
- Skills are discoverable.
- Custom agents are invocable.
- The plugin can be versioned and upgraded.
- The plugin can be removed cleanly.
- Documentation lets a new developer validate the setup in under one page.
- The old SDK path is not needed.

## Why It Is Necessary

- Manual setup does not scale across projects or teams.
- Plugin packaging turns the proof into a distributable capability.
- It allows Kast behavior to be standardized without copying loose files between repositories.
- It creates a reviewable artifact for enterprise adoption.
- It gives the team a clean boundary between Kast core, Copilot configuration, and enterprise policy.

## Phase 5 Audit Questions

- Does the plugin package every required moving part?
- Can it be installed into a fresh repository?
- Can it be versioned?
- Can it be removed cleanly?
- Are agents, skills, hooks, and LSP config all discoverable?
- Is plugin behavior equivalent to the manually wired setup?
- Does the plugin accidentally reintroduce the deprecated SDK path?

---

# Cross-Phase Completion Gates

## Gate A — No SDK Dependency

The plan is not complete if normal use still requires the Copilot SDK adapter.

Evidence required:
- SDK adapter not invoked by LSP.
- SDK adapter not invoked by hooks.
- SDK adapter not invoked by plugin.
- Tests pass with SDK code disabled or absent.

## Gate B — Compiler Facts First

The plan is not complete if Copilot still primarily relies on grep and file dumps for Kotlin symbol questions.

Evidence required:
- Transcript showing LSP use for definition.
- Transcript showing LSP use for references.
- Transcript showing LSP use for workspace symbols.
- Hook denial for broad search where symbol navigation is available.

## Gate C — Bounded Context

The plan is not complete if Kast or hooks inject large unbounded output into the model context.

Evidence required:
- Maximum response sizes defined.
- Large output summarized or written to artifact paths.
- Hook output capped.
- LSP responses remain location-structured.

## Gate D — Fail Closed on Unsafe Knowledge

The plan is not complete if stale, ambiguous, partial, or missing facts silently become model guesses.

Evidence required:
- Stale index test.
- Ambiguous symbol test.
- Partial reference set test.
- Unsupported rename test.
- Clear user-facing error states.

## Gate E — Write Operations Require Validation

The plan is not complete if Copilot can perform mutation without validation.

Evidence required:
- Post-edit diagnostics.
- Changed-file summary.
- Agent-stop validation gate.
- Hook test proving completion is blocked without validation.

---

# Recommended Audit Rubric

Score each phase from 0 to 3.

- 0: Not implemented.
- 1: Implemented manually or partially, but not repeatable.
- 2: Implemented and testable, but missing failure-mode coverage.
- 3: Implemented, testable, failure-aware, and independent of the deprecated SDK path.

Minimum acceptable public-demo threshold:

```text
Phase 1: 3
Phase 2: 3
Phase 3: 2
Phase 4: 1 or 2, depending on whether rename is part of the demo
Phase 5: 1 for local demo, 3 for team rollout
```

Minimum acceptable team-rollout threshold:

```text
Phase 1: 3
Phase 2: 3
Phase 3: 3
Phase 4: 3
Phase 5: 3
```

---

# Summary

The core requirement is to move Kast from a fragile Copilot-specific integration to a standard, compiler-grounded language intelligence surface.

The correct dependency direction is:

```text
Kast compiler index
  -> Kast daemon / RPC
  -> Kast LSP
  -> Copilot CLI LSP
  -> hooks / skills / agents
  -> plugin packaging
```

The incorrect dependency direction is:

```text
Copilot behavior
  -> bespoke SDK wrapper
  -> inferred Kast intent
  -> unreliable tool use
```

The plan is successful when Copilot can use Kast facts automatically, unsafe behavior is mechanically constrained, write operations are gated by compiler-backed knowledge, and the entire setup can be distributed without relying on the deprecated SDK path.
