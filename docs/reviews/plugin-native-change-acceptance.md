# Existing-IDE declaration change acceptance

Status: the installed matrix passed on September 11, 2026, at clean source
`aa95c7652a7d713f6bae00ed6a82fbc1a1080d43`: 126/126 read cases and 30/30 native
change cases. The canonical catalog now includes the three deferred change tools.
Final release artifacts must pass the same matrix at their exact source identity.

## Qualification record

The native run used IDEA `262.10315.125` with Kotlin `262.10315.125-IJ`.
Its receipt reports `passed=true`, `releaseQualified=true`, no remaining matrix
rows and successful removal of the private fixture. The
[complete bounded receipt](receipts/plugin-native-change-aa95c765.json)
has SHA-256 `6f0cb6ffac3887c03aac36158f61f6faad3a5ad867f05b7ee0f2469c4267cfd4`.

| Staged input | SHA-256 |
|---|---|
| Control product tree | `bdf788cf00083e25706d3a8dce583198ef97725ced8f6c36f61331b1036b6427` |
| Schema tree | `9522acbdef23c826a8608bffb9b3d8a9bab0e93793fd74753d43c39ea873be02` |
| Hosted plugin archive | `af5cfb72bbef91c70a68a992a065ef6f270a344ad71e59d265156f1932870a43` |
| Native broker harness | `cabbae6045c0bcb8e2d14a631b4b1ba8b8a8641525dda33198b53d0caf8fa3b0` |
| Test-only IDE probe | `00ed82515a2c2d44fc5b35115e7a1d3b6cdbbacac68ed2f95e5525c1ed5dc889` |

The product and schema values identify ordered file trees, not archive bytes.

| Native boundary | Observed result |
|---|---|
| Search, plan, approval, apply and verification | Unchanged reference; planning leaves saved source unchanged; verified before/after receipt; independent PSI observation |
| Root, intent, reference, epoch, owner, model and provenance | Foreign, unsupported, invalid, retired, moved and generated states rejected |
| Indexing, dirty document, approval decline/cancel/malformed and edited preimage | Rejected without additional source effects |
| Approval wait and concurrent/repeated apply | Workspace available during approval; one effect; existing receipt reused |
| Owner restart and recovery | Historical receipt retained; fresh approved exact-image rollback; divergent document preserved |
| Post-save interruption | Retry fenced with `WORKSPACE_RECOVERY_REQUIRED`; zero new invocation; broker stores retained; separately approved rollback |
| Plugin unload and lost response | Pending approval retired; attempted plan not replayed; durable verified state retrieved |
| Undo and divergent saved content | Undo removed only the latest change with saved/committed PSI; recovery preserved later disk edits |
| Separate broker process | Verified receipt retrieved; saved source unchanged |
| Read regression and routing | 126/126 reads; unchanged query budgets; 96 recorded CLI processes with zero isolated-startup commands |

Earlier native runs exposed asynchronous VFS save completion and shared Undo
command-group defects. The product now awaits per-file physical write completion
before observing saved bytes and gives each mutation session a distinct command
group. The full passing run includes both regressions. Startup readiness
classification remains setup-only and does not retry measured operations.

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
