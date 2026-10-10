# Workspace readiness and recovery proof

Issue [#989](https://github.com/amichne/kast/issues/989) changes workspace recovery after a read fails. It preserves the separate fence for uncertain writes.

## Decisions

- IntelliJ adapters own project, model, compiler, index, epoch and native settlement observations. `WorkspaceCapabilityReadiness` carries detached facts for `MODEL_PREPARATION`. Semantic operations retain their stronger admission and final publication checks.
- `WorkspaceReadinessPreparation` consumes current native facts. A usable model completes preparation without a reload. Opening revisions and registration cannot override that decision.
- `WorkspaceRefreshService` owns explicit refresh and incremental read preparation. Waiters share proven equivalent work. Each native attempt has an identity distinct from its work stamp.
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
| Reachable inspection and eventual admission | `WorkspaceDemandSettlementRecoveryTest`, `WorkspaceInspectionDeadlineRecoveryTest`, `IdeLifecycleInspectionProjectionTest`, `WorkspaceReadinessInspectionTest`, `WorkspaceRefreshInspectionTest` |
| Bounded shared refresh and retry | `WorkspaceRefreshServiceTest`, `WorkspaceRefreshGeneratedTransitionsTest`, `WorkspaceRefreshStartBoundaryTest`, `WorkspaceRefreshImportCompletionTest` |

Generated bounded sequences assert every prefix. They cover model movement, disposal, reopening, stale callbacks, duplicate requests, cancellation around refresh and lost callbacks. The broker composition regression runs real preparation, demand and execution transitions. A companion refresh regression follows M0, refresh, fresh M1, read cancellation, delivery failure, inspection, settlement and a fresh read. Both retain uncertain-write negative controls.

These sequences capture the screenshot's failure class. They do not establish its incident cause.

## Mutation evidence

Each mutation below was applied to production source, run against the focused tests and restored. Every mutation produced assertion failures. Compiler and runner failures were excluded from the proof.

| Deliberate mutation | Rejection |
| --- | --- |
| Restore permanent uncertainty fencing for reads | Five assertions in broker settlement and former sticky-read regression tests |
| Route inspection through the semantic lane | Five assertions in broker settlement properties |
| Cancel the adapter operation without awaiting termination | Provider-capacity regression assertion |
| Reload a currently usable model | Four preparation assertions |
| Identify refresh completions by equivalent work instead of attempt | Stale retry callback assertion |
| Clear the active native attempt when waiters expire | Four refresh assertions, including generated prefixes and missing callbacks |
| Discard M0 when current observation rejects | Retained-model assertions; generated coverage also rejects the missing typed evidence |
| Accept a moved final epoch | Two freshness assertions, including generated prefixes |

## Limits and native obligations

Pure tests prove decisions conditional on truthful native observations, proven settlement and fair processing. The IntelliJ modules alone own validation of actual native guarantees. No competing native qualification or IDEA instance was launched for this issue.

Existing Gradle import observation correlates the native task and application import completion. A missing callback remains unresolved. An exception before native scheduling produces a finite retryable failure. Once scheduling may have started, an exception alone cannot release native capacity.

Equal current project identity and epoch prove equivalent model reload demand. When no current epoch can be observed, separate new demands remain queued conservatively. Retained M0, matching reasons and installation metadata cannot prove their equivalence. Extending that case requires a truthful native change observation; this change does not invent one.

Historical M0 remains informational through refresh or rejected observation. Only a new current observation and final epoch validation establish fresh authority. A retired incarnation stays retired. A successful model refresh cannot reconcile an uncertain edit.

The exact-head aggregate build and independent source review remain merge gates. Native truthfulness remains an adapter obligation, not a claim established by the policy suite.

The required text assessment was attempted with the bundled procedure. It reported `VALE_UNAVAILABLE`; no engine was installed for this task. The report remains a documentation-check limitation. Source-knowledge and JSON guards passed without weakening their rules.
