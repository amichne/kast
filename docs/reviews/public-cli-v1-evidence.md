# Public CLI version 1 evidence

Version 1 remains incomplete until every acceptance criterion passes at its stated scope.
This report covers revision 1 of `kast-cli-specification.md`, dated October 29, 2026.

## Source identity

- Requested baseline: `7135d0f2a847a28b919bdba84bbbc8253f8d885a` from PR #1136.
- Refreshed PR state: `MERGED`.
- Refreshed PR head: `7135d0f2a847a28b919bdba84bbbc8253f8d885a`.
- Task branch: `feat/public-query-cli-v1`.
- Initial detached revision: `a96e7a07d92dbb3c96893a16559b9e35da99a7c5`.
- Preserved initial reference: `preserve/cli-spec-start-a96e7a07`.
- Initial implementation commit: `4db45af85af8d2529a0c23bf079ec4bfc16c59ae`.
- Test-owner repair: `65541c554b8ca2653b0432d083fcb6e660eeee9a`.
- Ingress-policy repair: `bea2b0f0696ed91a878977e7e439e88929739361`.
- Source-gate revision: `f4bcf2ebc5ae07684aefa129da944ea3ae995a01`.
- Source-gate tree: `cb0fe1cb0cd7ad7b744ec7d9658362934d91c6fe`.
- Cancellation repair and final candidate: pending.

The PR head did not change from the specification baseline.
The task branch started at that head.
A local ancestry merge later included PR #1136's squash commit, `ffd8eb410d756414cd87482397bcc0127966744f`.
The integration owner first established equality between the requested baseline tree and the squash tree.
That merge changed no task-tree files and did not merge a GitHub PR.
Reported results from prior work remain inherited evidence and do not count as passes here.

The source refresh used these commands:

```sh
git status --short --branch
git remote -v
git rev-parse HEAD
gh pr view 1136 --repo amichne/kast --json number,title,state,headRefName,headRefOid,baseRefName,url
git fetch origin pull/1136/head
git branch preserve/cli-spec-start-a96e7a07 HEAD
git switch -c feat/public-query-cli-v1 7135d0f2a847a28b919bdba84bbbc8253f8d885a
```

## Acceptance record

`PASS` needs every assertion in the criterion at its stated scope.
An infrastructure failure does not establish a product failure or a regression test failure.
`NOT RUN` means that the complete criterion lacks executed evidence.

| Criterion | Independent expectation | Status | Evidence or remaining scope |
| --- | --- | --- | --- |
| AC01 | The public candidate returns fixture declarations and relations without MCP. | PASS | Candidate `4db45af85` returned the exact declaration and caller edge with candidate MCP launchers disabled. |
| AC02 | Flags and explicit JSON admit equal requests and ordered relations. | PASS | `PublicCliArgumentsTest` covers independent documents, all sources, and reversed relation order. |
| AC03 | File and stdin agree. Malformed and mixed inputs reject before host work. | PASS | Parser rejection cases and `PublicCliProcessTest` passed, with a forbidden host factory and real input children. |
| AC04 | The selected catalog stays exact. `COMMON` rejects advanced syntax. | PASS | `PublicCliArgumentsTest` preserves explicit catalog restrictions and the private `COMMON` default. |
| AC05 | Omitted, empty, and selected fields stay distinct. Identity and outcomes persist. | PASS | Parser cases and `QuerySymbolFieldProjectionTest` passed, with exact encoded rows in both detail modes. |
| AC06 | Complete proof alone can succeed. Retained policy rejection uses exit 10. | PASS | Typed classification and real native/JVM children preserve exits 0, 10, and 1. Both retained rejection pages use 10. |
| AC07 | Complete `SOURCE_RELATIONSHIPS` preserves legitimate success. | PASS | `PublicShellExitTest` preserves success for the typed source-relationship interpretation. |
| AC08 | Pages preserve complete proof, distinct progress, and issued identities without producer replay. | PASS | Retained-read controls and the successor exit case passed. The 150-row fixture asserts one provider call. |
| AC09 | A selected row subset keeps `ROW_SELECTION_INCOMPLETE`. | PASS | `QueryRetainedRowAdmissionTest` passed all 10 cases, including subset qualification and unknown-row rejection. |
| AC10 | Another process can read an issued handle under the original host authority. Invalid authority rejects. | PASS | Candidate `4db45af85` preserved issued tokens across processes. Deterministic lifetime controls passed. |
| AC11 | Metadata and planning need no workspace, host, or network. | PASS | Real metadata and planning children passed. Forbidden session factories and missing-schema cases passed. |
| AC12 | 4,095 bytes reject. 4,096 bytes admit. Omitted grants use owner defaults. | PASS | Admission limits, forbidden host effects, and both configured-default pairs passed. |
| AC13 | A successor does not imply missing delivery. A fallback keeps its delivery failure. | PASS | Real output cases preserve complete pagination with exit 0 and delivery failure with exit 11. |
| AC14 | Signals cancel owned work. The launcher closes its socket and reaps its JVM child. | PASS | Queue, socket, and child-reaping cases passed. The retained blocked-writer regression now exits 130 without a reader drain. |
| AC15 | The first accepted terminal event controls exit and prevents a second document. | PASS | Both terminal-event orders, closed-consumer exit 5, and blocked-writer interruption passed. |
| AC16 | Existing RPC and MCP shapes, catalogs, and exits stay compatible. | PASS | Focused RPC, MCP, catalog, and encoded-output regressions passed. |
| AC17 | Packaged examples admit, and descriptors match parser defaults and variants. | PASS | Packaged examples, misspelled-example rejection, descriptors, output schemas, and public generation guards passed. |
| AC18 | Matched direct and public calls preserve semantic facts and record separate costs. | NOT RUN | Final candidate parity and matched measurements remain pending. |

## Existing executable oracles

The retained, budget, and lifecycle batch passed 63 cases across 12 suites, with zero skipped cases, failures, or errors.
The batch used the task branch during implementation. The selected test sources stayed unchanged.
The log is `/tmp/kast-cli-retained-characterization.log`.
The [suite record](public-cli-v1/retained-characterization.json) preserves counts from the JUnit XML files.

- `QueryRetainedRowAdmissionTest` rejects a mismatched output kind and unknown row identities before semantic execution.
- The same class asserts complete small pages, stable row identities, and `ROW_SELECTION_INCOMPLETE` for explicit subsets.
- `HostedQueryRetainedPresentationTest` keeps row and evidence progress separate across pages.
- `HostedCompletionRejectionPublicationTest` keeps the original rejection and `POLICY_REJECTED_EVIDENCE` in retained reads.
- `HostedContinuationOwnerRetentionTest` rejects owner resurrection and revokes detached output after epoch retirement.
- `HostedQueuedConnectionCancellationTest` closes a queued peer without dispatch.
- `HostedConnectionAdmissionTest` keeps independent reads available while disconnected dispatch drains.
- `PublicExecutionBudgetTest` preserves explicit allowances and rejects malformed dimensions.

The exact command was:

```sh
mise exec -- ./gradlew \
  :runtime:hosted:test \
  --tests '*HostedQueryRetainedPresentationTest' \
  --tests '*HostedContinuationOwnerRetentionTest' \
  --tests '*HostedCompletionRejectionPublicationTest' \
  --tests '*HostedQueuedConnectionCancellationTest' \
  --tests '*HostedConnectionAdmissionTest' \
  :query:protocol:test \
  --tests '*QueryRetainedRowAdmissionTest' \
  --tests '*CanonicalQueryRetentionProtocolTest' \
  --tests '*QueryCheckpointReplayTest' \
  :app-server:test --tests '*PublicExecutionBudgetTest' \
  :workspace:intellij-read:test \
  --tests '*HostedQueryExecutorTest' \
  --tests '*HostedQueryBackpressureTest' \
  --tests '*HostedReadActionAccountingTest'
```

`HostedQueryExecutorTest` passed the case that keeps admission until owned analysis drains after cancellation.
`HostedQueryBackpressureTest` passed queued expiry, cancellation, retirement, and capacity cases.
`HostedReadActionAccountingTest` passed separate cancellation and native-drainage facts.
These fixtures do not establish a real IDEA result or public launcher behavior.

The integration owner also reported this baseline command as passed:

```sh
mise exec -- ./gradlew :cli:test \
  --tests '*GeneratedCliProjectionTest' \
  --tests '*KastToolRpcResultAdmissionTest' \
  --tests '*KastToolRpcConstraintAdmissionTest' \
  --tests '*DirectCatalogArgumentsTest'
```

Its log is `/tmp/kast-cli-characterization.log`.
The shell exit regression first failed as expected in `/tmp/kast-cli-exit-red.log`.
The cases expected exits 10 and 11, but the baseline policy returned zero.
That failure establishes the missing shell policy, without a native semantic claim.

The parser owner reported this baseline command as passed before parser implementation:

```sh
mise exec -- ./gradlew :cli:test --tests '*PublicCliArgumentsTest' --console=plain
```

The single characterization case admitted 4,096 bytes and rejected 4,095 bytes through `PublicToolContract`.
It also admitted an explicit elapsed grant under `COMPLETE` and rejected that grant under `COMMON`.
Its log is `/tmp/kast-public-cli-parser-characterization.log`.
`PublicCliBudgetTest` later passed both configured-default pairs: 7 results with 8,192 bytes, and 13 results with 16,384 bytes.
Both use fixed ceilings and preserve the absence of a semantic timeout.

The candidate build log `/tmp/kast-cli-candidate-build-3.log` records successful CLI and distribution test tasks.
The whole build failed later at `architectureUnknownProjectPath(:)`.
That architecture failure does not erase the executed assertions or establish a candidate integration pass.
The CLI XML records 34 passed cases across seven public-shell suites, with zero skips, failures, or errors.
These suites cover parsing, discovery, budgets, child input, terminal-event order, runtime failure categories, and shell policy.
Their timestamps span `2026-10-29T21:55:42.925Z` through `2026-10-29T21:55:51.290Z`.

The integration owner later ran `QuerySymbolFieldProjectionTest` in `/tmp/kast-cli-final-oracles.log`.
The matrix passed all three field selections through the existing item projector and CLI document owner.
Its independent DTOs specify the exact row shape and values for default fields, no optional fields, and signature-only fields.
Both detail modes preserve identity and the completion model.
Qualified outcomes preserve coverage and incomplete-input qualification.

The [candidate adapter record](public-cli-v1/candidate-checks.json) contains 99 passed assertions with zero skips, failures, or errors.
The [handoff record](public-cli-v1/handoff-focused.json) contains 76 passed assertions with zero skips, failures, or errors.
The latter includes three real native/JVM composition cases.
Their output cases cover complete results, normal pagination, two policy-rejected pages, delivery failure, and a local defect.
Each delivered result contains one JSON document and one newline.
The controlled execution adapter supplies typed results. It does not simulate compiler authority.
The selected-JBR failure case also passed, with structured exit 4 and closed-consumer exit 5.

The [command record](public-cli-v1/verification-commands.txt) preserves the exact final focused commands.
The complete source gate passed at `f4bcf2eb`. A later adversarial output case exposed the remaining cancellation defect.

## Blocked output regression

The [raw reproduction](public-cli-v1/blocked-stdout-red/report.json) records a JVM child with an open stdout pipe and no reader.
The input was `describe query --section output`.
A normal reader received 3,129,373 bytes and exit 0.
The precondition thread dump placed the worker in native `FileOutputStream.writeBytes` and the main thread in `join`.
After `SIGINT`, both stacks remained in those states.
The child exited 130 only after the harness resumed the reader, which then received 68,994 bytes.
The independent expectation is signal settlement without a stdout drain.
The actual stack precondition establishes this failure. An elapsed timeout does not define the regression.
The raw record preserves the exact JVM, classpath, command, and thread dumps.
`PublicOutputCancellationTest` preserves that stack-based precondition and the normal-reader control.
The production writer now uses an interruptible channel under the existing invocation owner.
Both retained cases passed: blocked-writer `SIGINT` exits 130 without a reader drain, and a closed consumer exits 5.
The same command also passed the invocation-owner cases, static rules, formatting, and JSON guard.
The log is `/tmp/kast-cli-blocked-output-green.log`.
The [suite record](public-cli-v1/blocked-output-green.json) preserves the executed assertions.
A successor candidate and final source gate remain pending.

## Repository gates

The JSON, architecture, verification-graph, public-query generation, and knowledge guards passed.
The final focused build failed only a file-name lint rule. The corrected file passed `:cli:spotlessCheck` and `:cli:detekt`.
Its log is `/tmp/kast-cli-static-final-2.log`.
Earlier task-graph and static findings were repaired before this result.

The first documentation gate found generated provenance drift.
The documented generator refreshed provenance after the final source edits.
The documentation gate passed, including 382 parser, glossary, and projection cases, plus eight readability-tool cases.
Its log is `/tmp/kast-cli-documentation-final.log`.
No source OpenWiki pages changed.

The direct source-check script lacked `KAST_RELEASE_JDK25` in its first preflight.
The next preflight rejected the uncommitted tree.
Neither preflight establishes a product failure.
The wrapper later returned `SOURCE_VALIDATED` at `f4bcf2eb`, with 604 tasks in 42 seconds.
The [source record](public-cli-v1/source-execution-f4bcf2eb.json) preserves the exact revision, tree, task selection, and skips.
The full `--continue` source gate also passed 614 tasks in 6 minutes and 43 seconds.
Compiler fixture tasks used the repository's default disabled state.
Three `semanticCoverageFixtureTest` tasks and three semantic inventory or coverage guards were skipped.
Those skipped tasks do not supply compiler evidence. The disposable IDEA supplies native compiler evidence.

Pinned Vale and the separate prose assessment cover all changed prose paths, including `distribution/cli/AGENTS.md`.
The assessment status is `ASSESSED`, separate from a clean report.
Necessary technical terms and exact authority limits remain in the prose.
Readability scores do not establish reader comprehension.
The [Vale report](public-cli-v1/prose-vale.json) passed with no error findings.
The [assessment report](public-cli-v1/prose-assessment.json) preserves grade advisories for all six documents.
Exact identifiers and authority distinctions explain the remaining technical vocabulary.
`docs/tool-rpc.md` also has inherited diction findings at lines 29 and 199, plus a sentence-length finding at line 144.
`distribution/cli/AGENTS.md` has an inherited sentence-length finding at line 31.
Those unchanged passages remain outside this focused documentation edit.

## Native environment

Read-only inspection found this local environment:

| Item | Value |
| --- | --- |
| Machine | macOS ARM64, Darwin 25.5.0 |
| IDEA application | `/Users/amichne/Applications/IntelliJ IDEA.app` |
| IDEA version | 2026.2.3 |
| IDEA build | `262.10968.63` |
| Minimum Java version | 25 |
| Project Java tool | `oracle-graalvm-25.0.2` |
| Pinned platform fixture build | `262.9437.185` |
| Existing host-observation carrier build | `262.10315.125` |

The local IDEA application exists, but its version differs from both exact fixture pins.
This difference does not prove that the candidate Host rejects the application.
The installer admits the IntelliJ release line through its own contract.
The disposable candidate uses isolated configuration, system data, plugins, and a Kotlin workspace.
The selected personal installation remains outside this task's mutation scope.

The disposable fixture is `/private/tmp/kast-public-cli-v1-a3b5uat_`.
Its status is `PRELIMINARY_NATIVE_SMOKE_PASSED`.
It contains separate IDEA paths, a copied VM options file with an isolated `user.home`, and an independent Kotlin source fixture.
The separate paths use the [documented IDEA directory properties](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html).
The fixture defines `example.OrderService`, `example.OrderService.submitOrder`, and its caller `example.placeOrder`.
The initial query uses `RETAIN`, 128 results, 49,152 bytes, and `NAME`, `SIGNATURE`, and `LOCATION` fields.
The planned sample count is three per route.
The generated Host installer returned `ACTIVATED` for the isolated plugin path.
The candidate archive is `kast-ide-hosted-v0.0.0-idea-262.zip`, with SHA-256 `70b73ebbc3161c9cb3a31dbca17c142cd0612a3f4524f4de6e75896382722e98`.
Its record identifies the baseline revision, with changes in the task tree. It does not identify a final implementation commit.
The current candidate IDEA process has PID 23194.
Its log reports `Kast Existing IDE Runtime (0.0.0)` and all isolated directory properties.

The first process stopped before project import.
Its thread dump showed `Agreements.showDataSharingAgreement` under `app pre-initialization`.
The UI adapter resolves the application path to a personal project window instead of the owned candidate.
No UI action changed that window, and no agreement was accepted.
The candidate uses the [JetBrains test-launch property](https://github.com/JetBrains/intellij-ui-test-robot) `jb.consents.confirmation.enabled=false` for optional telemetry.
That property leaves missing consent undefined. It does not accept an agreement.
The second process reached the workspace trust prompt.
The isolated trust configuration now names only the owned workspace.
The first two processes stopped and were reaped before each restart.
The first workspace launch rejected its endpoint directory because the Unix socket path exceeded the existing 100-byte limit.
The candidate home now uses `/private/tmp/kcv1h-a3b5uat`, with an 86-byte socket path.
The Host published its exact-workspace endpoint at `18:07:33` local time.
Gradle import completed at `18:07:41`, with a recorded duration of 8,634 milliseconds.
The current endpoint identifies host `d0fa38e6-2249-45c3-895d-a570159d1a92` and schema `kast.query.run.v9`.
The preliminary public query returned `example.OrderService` with complete compiler proof.
A later process read the issued handle and preserved its exact reference, row identity, workspace, host, and basis.
The caller query returned only `example.placeOrder` and its compiler-confirmed call to `submitOrder`.
Candidate MCP launchers lacked execute permission during the retained read and caller query. Their original modes were restored.
These samples establish feasibility. They do not bind the final implementation commit.

The preliminary Control archive has SHA-256 `fafaa7af935f188174e6ca17dd526df084d482227266cfef1fdf70412dbe5777`.
The [native records](public-cli-v1/native-smoke-records.json) preserve command vectors and expected host identities.
The [query input](public-cli-v1/query.json) and [source fixture](public-cli-v1/OrderService.fixture.txt) define the independent question and declaration oracle.
Raw stdout and stderr files accompany those records.

The first public invocation used `KAST_OPTS`, which the generated query script does not consume.
Its JVM used the personal home and reached personal IDEA process 79278, with host `c65e9988-626e-4a9b-b06a-da48615627e9`.
The sample is discarded because that authority differs from the candidate endpoint.
The corrected harness uses `JAVA_TOOL_OPTIONS=-Duser.home=/private/tmp/kcv1h-a3b5uat` for every child JVM.
All accepted preliminary samples match candidate PID 23194 and its exact host identity.
The discarded call opened only the owned fixture workspace in the personal IDE.
That incidental open remains because the UI adapter cannot target its exact process safely.
No personal installation changed, and no personal IDE process stopped.

The exact startup diagnostic was:

```sh
'/Users/amichne/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/jcmd' \
  17876 Thread.print > /private/tmp/kast-public-cli-v1-a3b5uat_/evidence/startup-threads.txt
```

The fixture evidence directory also contains the installer result, IDEA logs, and the process identity record.

## Committed candidate native evidence

The [candidate record](public-cli-v1/candidate-4db45af85-native/candidate.json) binds the native samples to producer `4db45af85`.
Its Control archive has SHA-256 `ecca4ee30eb2a36299faad71e7e5983f20e6f0452da06962a24a39f46a2f5606`.
The Host archive kept SHA-256 `70b73ebbc3161c9cb3a31dbca17c142cd0612a3f4524f4de6e75896382722e98`.
The test and policy successors do not relabel these immutable artifacts.

The first upgrade try rejected the preliminary installation with `REGISTRY_REJECTED` and rolled back.
The existing installer rejected broker state without a workspace registry.
The public stop command then rejected its service child. A direct service-disable command with explicit Java and configuration paths succeeded.
The precise cause of the public stop rejection remains unclassified.
The old broker socket disappeared, and no JVM from that installation remained.
No registry ownership rule changed.

A normal clean installation succeeded under `/private/tmp/kcv1f-a3b5uat`.
The generated Host installer returned `ACTIVATED` for that home's plugin path.
Only the owned IDEA process restarted.
Its PID is 31895, and its host identity is `cda95f2e-e6fd-4b11-9155-69b86bc6e83c`.
The selected runtime is JetBrains JBR `25.0.4+1-508.27-nomod`.
The machine is an Apple M1 Max with 10 CPUs and macOS 26.5.2.

The [native acceptance record](public-cli-v1/candidate-4db45af85-native/acceptance.json) preserves the exact endpoint and assertion results.
The declaration query returned only `example.OrderService` at offsets 17–94 with complete compiler proof.
The caller query returned only `example.placeOrder` and one compiler-confirmed edge to `example.OrderService.submitOrder`.
The call occurrence spans offsets 152–163.
A separate process read the issued result and preserved its row, symbol reference, retained reference, and live authority.
Candidate MCP launchers lacked execute permission during every call. Their original modes were restored afterward.
These samples describe the first candidate. The repaired candidate still needs native qualification.

## Measurements

Native measurements are NOT RUN.
No latency, byte, physical-call, or semantic-work improvement is claimed.
Parity must pass before a performance comparison can support an improvement claim.
Each sample must keep the same request, workspace, scope, fields, budgets, and host state.

## Handoff limits

The successor candidate, matched measurements, and final source gate remain pending.
Normal rollback uses a source revert and removal of the owned candidate artifacts.
No semantic-state migration belongs to this implementation.
