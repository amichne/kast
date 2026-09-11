# Existing-IDE declaration change acceptance

Status: full native qualification is pending. Change tools remain excluded from
the canonical default catalog until the installed matrix passes.

## Supported boundary

The hosted path accepts `AddDeclaration` in one uniquely owned, authored Kotlin
source file in the existing IntelliJ project. Search references pass unchanged
through the provider and CLI into live planning admission. The plan retains
historical root, owner, epoch, model, source preimage, write set and verification
obligations. Planning grants no write authority.

Apply requires approval of the immutable plan, fresh mutation admission and
durable recovery preparation. It executes the source change in an IntelliJ
command/write action, records the applied image, saves the document, waits for
the per-file physical VFS write and observes the exact postimage. Live semantic
verification is part of apply. A verified receipt retains scoped before/after
evidence without creating a published workspace generation.

## Evidence boundaries

`hostedChangeAcceptance` runs a dedicated imported fixture in a private IDE
sandbox with staged release artifacts. Setup includes fixture creation, initial
Gradle import and plugin installation. Measured requests use the production
broker, provider, CLI and hosted plugin. A scripted native protocol controller
supplies the client/upstream transport and approval decisions.

The separately packaged fixture probe observes saved bytes, document state,
direct-container PSI declarations and the actual Undo command. Its controls
introduce indexing, model movement, dirty content, plugin unload and a post-save
interruption. It is not part of the product plugin. Both the product and probe
wait for per-file asynchronous save completion before physical observation.

The runner preserves artifact digests, exact IDEA/Kotlin identities, case
outcomes, source hashes and scoped semantic evidence. Diagnostic output excludes
source payloads, approval secrets and executable reference tokens. Runtime
process snapshots and log observations retain their limits; structural route
checks separately exclude workspace-opening and isolated-worker capabilities.

Deterministic tests cover mutation ordering, persistence failures, cancellation
and finite effect states. They complement the native lifecycle cases and do not
substitute for distributed execution.

## Verification gates

The repository gate covers compilation, tests, architecture, packaging,
generated contracts, knowledge validation and the JSON construction ratchet:

```shell
./gradlew knowledgeImpact verifyKnowledgeBase
./gradlew :app-server:verifyPublicQueryGeneration
./gradlew productBuildGate
```

Native qualification is opt-in and separate. See the root
[development instructions](../../README.md) for the required staged artifacts
and `hostedChangeAcceptance` properties. A dirty-tree diagnostic run cannot
qualify a release.

## Interpretation limits

The first increment does not support adding files, replacing declarations,
multi-file rename, arbitrary text edits or unsaved buffers. Scoped verification
does not prove a whole-project build. The native fixture does not establish
arbitrary-repository scale or every possible cancellation interleaving.

A missing apply response does not establish that no effect occurred. An
uncertain broker invocation fences the workspace with
`WORKSPACE_RECOVERY_REQUIRED`. The harness replaces that broker while preserving
its invocation journal and thread store before separately approved recovery.
It does not clear the fence in production or revive an attempted plan.

Stock Codex Desktop and TUI compatibility remains the separate gate in the
[App Server compatibility record](../../app-server/docs/compatibility.md).
Native protocol-controller success cannot establish that client UI/history
boundary.
