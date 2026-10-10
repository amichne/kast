# Workspace readiness and recovery proof

Issue [#989](https://github.com/amichne/kast/issues/989) changes workspace recovery after a read fails. It preserves the separate fence for uncertain writes.

## Decisions

- IntelliJ adapters own project, model, compiler, index, epoch and native settlement observations. `WorkspaceCapabilityReadiness` carries detached facts for `MODEL_PREPARATION`. Semantic operations retain their stronger admission and final publication checks.
- `WorkspaceReadinessPreparation` consumes current native facts. A usable model completes preparation without a reload. Registration and opening metadata cannot override that decision. An observed change to a Gradle input requires a successful native import covering that change revision; an old Ready-looking model cannot discharge it.
- `WorkspaceRefreshService` owns explicit refresh and incremental read preparation. Waiters share proven equivalent work, including a retained active attempt after its earlier waiter expires. Each native attempt has an identity distinct from its work stamp.
- The native refresh owner also owns observed Gradle input revisions. An owner-issued import ticket captures coverage before native scheduling. Only successful import settlement discharges that captured revision. A later input change remains pending; attaching to an already-running import cannot retrospectively prove its coverage.
- Lifecycle observation and transition submission run off EDT. Native link preparation and scheduling remain explicit effects marshalled by their adapter.
- Cancellation and deadlines end a wait. They do not establish provider or native termination. Capacity remains held until the corresponding owner observes settlement.
- `WorkspaceExecution` permits fresh read demand after provider termination. The native adapter must still admit the request. Unknown effects and uncertain writes retain mutation reconciliation fences.
- Only schema-qualified lifecycle inspection and status bypass the semantic lane. Effectful lifecycle commands retain authorization and concurrency checks. Inspection combines native facts with bounded broker evidence and explicitly marks native authority unknown to the broker.
- Terminal preparation failures remain historical records. Later authorized demand asks the native owner again; it does not promote an old failure or a refresh callback into current authority.

## Policy evidence

Tests execute production rules with detached identity tokens, recording ports and virtual time. New policy properties use no native project, filesystem, Git, socket, registry or Gradle fixture. Build provisioning remains separate. Existing adapter tests retain their declared resource boundaries.

| Property | Production boundary and tests |
| --- | --- |
| Model sufficiency and metadata invariance | `WorkspaceReadinessPreparation`, `AutomaticReadinessTest`, `WorkspaceEpochValidationTest` |
| Fresh publication and retained evidence | `validateWorkspaceEpoch`, `HostedWorkspaceReadinessHistoryTest`, `WorkspaceRefreshServiceTest` |
| Failure containment and settlement | `WorkspaceExecutionSettlementPropertyTest`, `OutputContractRecoveryPolicyTest`, `HostedQueryLifetimeTest`, `HostedQueryExecutorTest` |
| Reachable inspection and eventual admission | `WorkspaceDemandSettlementRecoveryTest`, `WorkspaceInspectionIngressRecoveryTest`, `WorkspaceInspectionDeadlineRecoveryTest`, `IdeLifecycleInspectionProjectionTest`, `WorkspaceReadinessInspectionTest`, `WorkspaceRefreshInspectionTest` |
| Bounded shared refresh and retry | `WorkspaceRefreshServiceTest`, `WorkspaceRefreshGeneratedTransitionsTest`, `WorkspaceRefreshStartBoundaryTest`, `WorkspaceRefreshImportCompletionTest`, `WorkspaceRefreshModelBoundaryTest`, `WorkspaceRefreshObservationTest` |

Generated bounded sequences assert every prefix. They cover model movement, disposal, reopening, stale callbacks, duplicate requests, cancellation around refresh and lost callbacks. The broker composition regression runs real preparation, demand and execution transitions. A companion refresh regression follows M0, refresh, fresh M1, read cancellation, delivery failure, inspection, settlement and a fresh read. Both retain uncertain-write negative controls.

These sequences capture the screenshot's failure class. They do not establish its incident cause.

## Mutation evidence

Each mutation below was applied to production source, run against the focused tests and restored. Every mutation produced assertion failures. Compiler and runner failures were excluded from the proof.

| Deliberate mutation | Rejection |
| --- | --- |
| Restore permanent uncertainty fencing for reads | Five assertions in broker settlement and former sticky-read regression tests |
| Route inspection through the semantic lane | Five assertions in broker settlement properties |
| Misclassify qualified inspection at broker ingress | Two assertions in the composed ingress, transport and projection cases |
| Omit the broker's inspection reply projection | Two assertions in the composed ingress, transport and projection cases |
| Cancel the adapter operation without awaiting termination | Provider-capacity regression assertion |
| Reload a currently usable model | Four preparation assertions |
| Identify refresh completions by equivalent work instead of attempt | Stale retry callback assertion |
| Clear the active native attempt when waiters expire | Four refresh assertions, including generated prefixes and missing callbacks |
| Discard M0 when current observation rejects | Retained-model assertions; generated coverage also rejects the missing typed evidence |
| Accept a moved final epoch | Two freshness assertions, including generated prefixes |
| Duplicate a retained active attempt after waiter expiry | Public two-argument equal-epoch demand regression |
| Let a Ready-looking model erase pending input requirements | Three composed model-boundary assertions |
| Acknowledge inputs newer than the settled import's coverage | Two revision and composed-refresh assertions |
| Accept stale success after failed import settlement | Terminal-ticket regression assertion |
| Observe refresh on the caller's context | Explicit observation-dispatcher regression assertion |

## Limits and native obligations

Pure tests prove decisions conditional on truthful native observations, proven settlement and fair processing. The IntelliJ modules alone own validation of actual native guarantees. No competing native qualification or IDEA instance was launched for this issue.

Existing Gradle import observation correlates the native task and application import completion. A missing callback remains unresolved. An exception before native scheduling produces a finite retryable failure. Once scheduling may have started, an exception alone cannot release native capacity.

Equal current project identity and epoch prove equivalent model reload demand. When no current epoch can be observed, separate new demands remain queued conservatively. Retained M0, matching reasons and installation metadata cannot prove their equivalence. Extending that case requires a truthful native change observation; this change does not invent one.

Historical M0 remains informational through refresh or rejected observation. Only a new current observation and final epoch validation establish fresh authority. A retired incarnation stays retired. A successful model refresh cannot reconcile an uncertain edit.

The exact-head aggregate build and independent source review remain merge gates. Native truthfulness remains an adapter obligation, not a claim established by the policy suite.

## Review and aggregate follow-up

Review of `250440017c4e979cc04636f48ca1e7653ba3310a` found three production gaps: a later equivalent waiter could duplicate a retained active attempt after timeout; explicit refresh sampled readiness on the unsupported EDT; and successful refresh did not discharge a changed Gradle input revision. It also found that inspection tests did not compose the real ingress classifier and broker projection. The follow-up implements these corrections. Preparation, inspection, semantic preflight and legacy Describe consume the same owner input obligation.

The new inspection composition passes three focused tests. Two production mutations independently misclassified ingress and omitted the real broker projection; each failed both composed cases. The source was restored after each run. The test uses real schema qualification, execution transitions and reply projection around recording transport ports, without sockets or native work.

The required `run_product_gate.py` wrapper at that head selected release authority `0.50.0` and failed catalog admission. The packaged callback catalog used 1,062,423 bytes against the unchanged 1,048,576-byte limit. The installed document also failed its qualification headroom guard. Three exact schema reuses reduce the generated catalog to 930,575 bytes while preserving expanded canonical shapes. The four new schema proof tests pass, including exact canonical shape comparisons, all 25 readiness/refresh combinations and rejection of invalid facts. The comparator resolves references without allocating duplicate expanded trees; admission limits and runner memory settings remain unchanged. The admission limits remain unchanged. The earlier gate completed its required CLI native compilation without launching or installing an IDE.

Fifty-four focused refresh tests pass after the follow-up. Five further production mutations each fail their focused assertions and are restored. All seven affected module checks pass: app-server 631 (three existing installed-Codex skips), hosted runtime 307, workspace contract 42, native-read 278, protocol contract 106, protocol wire 196 and CLI 404. They report zero failures. Formatting, Detekt, file-length, architecture and source-knowledge checks pass. All refresh policy tests use injected clocks. Packaged runtime admission and the exact-head product aggregate remain required before merge.

The required text assessment was attempted with the bundled procedure. It reported `VALE_UNAVAILABLE`; no engine was installed for this task. The report remains a documentation-check limitation. Source-knowledge and JSON guards passed without weakening their rules.
