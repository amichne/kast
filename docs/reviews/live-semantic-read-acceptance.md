# Live semantic read acceptance

The default CLI path now sends the seven canonical semantic reads to the existing
IntelliJ project. Manual native execution established complete exact reads and
explicitly qualified bounded reads on the Kotlin fixture below. This acceptance
does not establish complete `ALL` enumeration, library parity, or full Codex
WebSocket integration. The production App Server provider also completed an exact
query through the final qualified CLI distribution.

## Qualified environment and evidence

- Date: 2026-09-10.
- Native IDEA build: `262.10315.125`.
- Plugin archive SHA-256:
  `2c9fd4a70be1bf49ba7edb13a340b9806464fa1eef9b611cc4fc1a8a59dc3667`.
- Existing Gradle fixture: `/private/tmp/klq-fixture-ekxvxa6l`, with three Kotlin
  source files and imported `main`, `test`, and `integrationTest` ownership.
- Final default-route CLI matrix: `/tmp/kast-native-cutover-final/`.
- Final plugin read, provider and lifecycle receipts: `/tmp/kast-native-final/`.
- Earlier saved-edit, dirty-content and peer-disconnect receipts:
  `/tmp/kast-native-matrix/`.
- Stage 3 `productBuildGate`, knowledge and architecture verification passed at
  `55aae3688`. This is the verified base for the separate default-route change;
  its local gate completed 252 tasks in 1 minute 37 seconds. Stage 3 exact-head
  CI also passed in run `34509994681` for pull request #707.
- The final CLI reports `kast 0.37.1-22-g55aae3688 (IntelliJ sidecar)`, but includes
  the subsequent stage 4 default-route and projection-policy working-tree changes.
  Its parent Git version metadata alone does not identify those changes. The
  staged distribution is recorded by `/tmp/kast-final-control-path`.
- Final CLI JAR SHA-256:
  `d29c889e667333cbb2e0483d3c6996a3dc04d29939690e41a89a1a2ecd60d7c6`.
- Final App Server JAR SHA-256:
  `c290e6880034c92e9507edf2e8452f7363da854c09c223c73c6dbb00a6917e7c`.

Receipt paths identify local manual artifacts from this run, not checked-in CI
fixtures. The tables retain the observed outcomes without copying executable
reference tokens. The initial final-plugin matrix used host
`5d4d9da7-9d6a-48d8-831c-01df1e994f7c`, epoch 1. After plugin restoration the host
identity was `9de9d635-ece5-479c-a7f3-fff95530a227`, epoch 1; the final CLI
matrix and provider invocation used that restored owner. Successful semantic
envelopes carry the canonical fixture root and `SAVED_PSI_COMMITTED` live evidence;
they do not claim a publication generation.

## Default route and authority

[`selectCliRuntimePath`](../../cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt)
selects `query`, `symbol`, `source`, `relation`, `traversal`, and `diagnostic`
before installed-runtime bootstrap. [`KastCliMain`](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt)
then uses the existing-host socket client. The closed read operation set is
`query.run`, `symbol.discover`, `symbol.inspect`, `source.read`, `relation.read`,
`traversal.run`, and `diagnostic.check`. Missing hosts reject; these reads do not
fall back to opening a workspace, importing Gradle, starting a worker, or
publishing a snapshot. The optional root delimiter is admitted before command
selection, so `kast -- query run` takes the same path as `kast query run`; the
original argument vector still reaches Clikt. A focused RED/GREEN check covers
all seven reads in both spellings without bypassing the existing-host boundary.

[`HostedCanonicalQuery`](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt)
composes the pure services and project-bound read ports under the original host's
admitted authority. Exact source ownership, saved documents, committed PSI,
freshness, budgets and final content/epoch validation remain admission obligations.
Reference decoding requires that current owner; it cannot create live authority.
The [wire binding](../../protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt)
also rejects contradictory envelope/source-snapshot evidence and mismatched live
traversal roots.

## Final native read matrix

The final CLI rerun receipts are in `/tmp/kast-native-cutover-final/`; the
default-versus-explicit custom-source-set and named-test receipts are in
`/tmp/kast-native-final/`. Both runs used the plugin archive identified above.
`Complete` means the requested bounded operation completed; it does not mean
the whole workspace was exhaustively analysed.

| Request | Observed result | Receipt |
| --- | --- | --- |
| Exact `SEARCH Child` | Complete, one exact `Child` | `child.json` |
| Exact `SEARCH call` | Complete, three distinct overload declarations | `overloads.json` |
| Exact `SEARCH helper` | Complete, exact top-level function | `helper.json` |
| Public visibility filter | Complete: `Child` and `visibleProperty` retained; `Hidden` and `PrivateContainer` excluded | `public-*.json` |
| `SEARCH helper` → `EXPAND CALLERS` → `DISTINCT` | Complete, calling `call` declaration | `helper-callers.json` |
| `REFS` → `DISTINCT` | Complete, one retained `Child` | `refs-distinct.json` |
| Default versus explicit custom source set | Complete: default excludes `CustomOnly`; `integrationTest` includes it; explicit `test` includes `TestOnly` | `default-custom-excluded.json`, `explicit-custom.json`, `named-test.json` |
| Absent exact source-set name or foreign package | Complete empty result under the admitted model/scope | `absent-source-set.json`, `foreign-package.json` |
| Default `ALL` and package-scoped `ALL` | Qualified, known minimum 11, `work-limit-reached` and `discovery-incomplete` | `all-default.json`, `package.json` |
| Custom-source-set broad `ALL` | Qualified, known minimum 1 with the same work/discovery limitations | `custom.json` |
| Specialist discover and inspect | Complete | `discover.json`, `inspect.json` |
| Specialist source and relation reads | Complete | `source.json`, `relation.json` |
| Specialist traversal, requested depth 1 | Qualified, terminal `depth-limit-reached` | `traversal.json` |
| Specialist diagnostics on the restored fixture | Complete, empty diagnostic list | `diagnostic.json` |

Broad symbol discovery now supplies a policy-matched project ID filter before
name collection and admits Kotlin's type-alias contributor. The final broad
receipts include `helper` and `PublicChild`; `visibleProperty` is still absent
from the bounded `ALL` result even though its exact query succeeds. The native
rerun therefore supports the returned partial coverage, not complete enumeration
or a general timing improvement. The budgets were not raised.

## Content, cancellation and original-owner lifetime

Saved-content observations in `/tmp/kast-native-matrix/` used the earlier host
`441fa476-2cbc-4496-af52-27cd0b787d41` and remain separate from the final package
matrix:

- `dirty-query.json` rejected unsaved content as `DIRTY_DOCUMENTS` at
  `MODEL_CAPTURE`.
- After an IDE edit was saved, the same host advanced from epoch 1 to epoch 2.
  `after-save-property.json` completed, and `after-save-source.json` returned the
  saved value `saved-after-ui-edit`.
- `old-ref-after-save.json` rejected the old reference as `stale-authority`.
- `diagnostic-type-error.json` completed at epoch 4 with
  `INITIALIZER_TYPE_MISMATCH`, identifying the deliberate `String`/`Int` error.
- `disconnect-evidence.json` records termination of an in-flight CLI client
  (exit 143), the host's rejected request, and a subsequent complete query on the
  same host. This is native peer-disconnect/recovery evidence; it is not a proof
  of every possible cancellation interleaving.

Final lifecycle observations in `/tmp/kast-native-final/` establish:

- `old-host-after-restart.json` rejects the prior host's reference as
  `incompatible-authority`.
- `retirement-evidence.json` records descriptor removal, `ACCEPT CANCELLED`, and
  `RETIREMENT STARTED` → `COMPLETED`, with no unhandled listener error.
  `closed-host.json` rejects as `ide-host-unavailable` while the project is closed.
- The project was reopened; `pre-retirement-ref-rejected.json` still rejects the
  retired owner's reference as `incompatible-authority`.
- `plugin-lifecycle/plugin-unloaded.json` records an actual Platform plugin-manager
  unload: `unloaded=true`, `stillLoaded=false`. Its `evidence.json` also records
  descriptor removal. `unloaded-plugin.json` rejects as `ide-host-unavailable`.
- After plugin restoration, `after-plugin-reload.json` completes under the new host
  identity above. Restoration does not revive old reference authority.

The listener and peer cancellation owners have separate deterministic socket
checks. Those checks support the implementation but are not substitutes for these
native project/plugin lifecycle observations. The original
`/Users/amichne/code/kast` project was reopened after qualification.

## Production App Server provider invocation

`/tmp/kast-native-final/provider-child.json` records a successful real process run
through production [`KastProviderQualifier` and `KastRuntime.invoke`](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastProvider.kt)
and [`Broker.dispatch`](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/Broker.kt). It qualified the staged CLI, admitted `SEARCH Child`, invoked
the actual executable, and validated the completed provider response. The result
contains one exact `Child` under host `9de9d635-ece5-479c-a7f3-fff95530a227`, epoch 1,
with saved, committed live evidence and no publication generation.

The qualified App Server projection is version 9, with contract digest
`sha256:75f7afdd711bed349b4f3e6d23e69a60572bb3e455027a656018050a8f82726b`.
`schema-budget.json` records 290,635 bytes for the final `--schema` document,
below the unchanged 524,288-byte limit.

`final-control-correlation.json` records exact equality between direct CLI and
provider results for the item and live root, host, epoch, and content evidence.
Process sampling observed the same IDEA process (PID 70408), the three pre-existing
Kast workers, and no new relevant IDEA, worker, or Gradle process during the direct
query. The [route owner](../../cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt)
and [host dependency boundary](../../runtime/hosted/build.gradle.kts) establish the
absence of open/import/worker capabilities more strongly than that process sample.
`final-control-missing-host.json` records exit 4 with `ide-host-unavailable`; the
isolated missing-host fixture still contained only its original
`settings.gradle.kts`, with no new runtime files.

The manual harness exercised the production provider and real CLI executor. It
did not exercise full stock Codex WebSocket framing, thread persistence, or
multi-client routing. Those remain separate acceptance boundaries.

## Verification boundary

Routine CI retains its existing [Kotlin product job](../../.github/workflows/ci.yml)
and [documentation job](../../.github/workflows/docs.yml).
[`verify-checks.py`](../../.github/scripts/ci/verify-checks.py) continues to run the
normal deterministic checks, architecture verification and `productBuildGate`.
This change adds no synthetic IntelliJ model, fake native-coverage claim, or new
IDE automation job to CI. Native qualification was performed manually in the
existing IDEA process. Stage 4 focused checks passed 72 tests: CLI 21, native
Unix-socket transport 9, registry 14, and provider 28, with no errors or skipped
tests. The delimiter regression was reproduced before the fix; the focused GREEN
rerun retained 21 CLI and 9 native socket checks with both ordinary and leading
`--` command forms. These deterministic checks remain distinct from the manual
native matrix.
The rebuilt packaged CLI also returned identical complete results for ordinary
and leading-delimiter queries (`leading-delimiter.json`); the production provider
rerun used that rebuilt distribution and retained matching live evidence.

CI timing varied. In #707 the product job took 10 minutes 25 seconds; its Gradle
product build reported 5 minutes 9 seconds, compared with 4 minutes 43 seconds in
#706. The dry-run graph/configuration interval was 4 minutes 20 seconds versus
27 seconds. The dependency-install step took 1 second, and the 35 existing Python
host-protocol tests took 0.317 seconds. The measured intervals do not identify the
cause of the configuration variation; cache or cold-compilation explanations are
unproven. Unchanged jobs and budgets do not establish unchanged pipeline duration.
Stage 4 CI will provide a separate measurement.

Remaining limits are explicit: broad discovery can be qualified, traversal can
stop at its depth bound, live specialist discovery excludes generated sources
and libraries, and full Codex WebSocket parity is unqualified. Published writes
and topology/publication flows retain their separate authority and execution paths. The earlier class/supertype demonstration
history remains in the [hosted-query knowledge page](../../knowledge/flows/hosted-query.md);
it is not used as evidence for the canonical results recorded here.
