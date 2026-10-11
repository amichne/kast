# Bounded Pi agent evaluation

This opt-in controller belongs to the existing host-observation experiment and
`hostObservationTest` gate. It is separate from the production Pi adapter,
retention accounting and the proposed invoke-once/await client protocol.

The 2026-10-10 public synthetic pilot exposed two controller defects: a terminal
`COMPLETION_UNPROVEN` reply did not stop the next model turn, and a 30,000-token
negative-control target could not cover both requests with roughly 17,000 input
tokens per request. Its later, explicitly authorized saved-result continuation
used 17,905 tokens and zero additional semantic calls. Sanitized numeric/shaped
regressions are in `pi_evaluation.test.mjs`; private transcripts stay outside Git.
The original pilot's guard abort was not transport proof: installed Pi's cached
WebSocket sends before observing that abort. This change uses isolated SSE and
turns off background cache warming; it does not revise the original raw evidence
or claim that the pilot's denied request reached a provider or incurred usage.

Run deterministic checks without Pi, authentication, IDEs or inference:

```sh
node --test experiments/host-observation/pi_evaluation.test.mjs \
  experiments/host-observation/pi_evaluation_payload.test.mjs \
  experiments/host-observation/pi_evaluation_records.test.mjs \
  experiments/host-observation/pi_evaluation_calibrations.test.mjs \
  experiments/host-observation/pi_evaluation_preflight.test.mjs
python3 -m unittest discover -s experiments/host-observation -p test_pi_evaluation.py
```

The actual SDK worker uses the same exported guard and rules tested here. Node
is required; a missing interpreter is a test provisioning failure, never a skip
or GREEN. The Python wrapper includes these checks in the existing Gradle gate.

## Rules and evidence

- Every named case owns fresh counters and a global reported-token threshold.
  Fresh execution allows at most three provider requests across WORK and DELIVERY;
  received-result execution allows one. Explicit smaller limits remain smaller.
  Phase request limits and output reserves also apply. The default global token
  threshold is the sum of the declared WORK and DELIVERY token amounts; an
  explicit `maximumReportedTokens` sets the case's global threshold.
- Provider admission checks the exact `openai-codex/gpt-6.1-sol` selection and
  effective `high` effort against the complete SDK serialized request. The whole
  request, including declarations, instructions, restored `additional_tools`
  and input, is capped at 262,144 bytes (256 KiB). An explicit smaller cap remains
  smaller. Unsupported structures fail closed; nothing is trimmed. Declaration
  and tool-text limits remain independent. Optional full-request calibration is
  empirical diagnostic evidence; missing or changed calibration does not deny
  a structurally supported request within these byte and request-count bounds.
  A novel body has a diagnostic input estimate: the greater of its complete
  serialized UTF-8 byte count and the previous measured cached-inclusive input.
  The nominal assumption is one token per UTF-8 byte. This conservative heuristic
  is uncertain, supplies no guaranteed token upper bound, and never decides
  admission. Its method is `FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE`
  with qualification `CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF`.
  A matching full-request calibration instead uses
  `VERIFIED_FULL_PAYLOAD_EMPIRICAL_CALIBRATION` and
  `CALIBRATION_NOT_TOKENIZER_PROOF`. The greater of its empirical ceiling and
  the measured input floor is still a diagnostic estimate. `required` adds the
  output reserve to either estimate without creating an admission oracle.
- Finalized API usage accumulates uncached input, cached input read/write and
  generated output across the case. Reasoning is a subset of output, never an
  extra debit. Reaching the global threshold stops subsequent requests. One
  response can overshoot the threshold; this is post-response accounting and
  provides no spend guarantee. A serialized assistant response is capped at
  65,536 bytes (64 KiB), with explicit smaller limits preserved. Fresh execution
  has a maximum 120-second deadline; received-result execution has 60 seconds.
  Shorter declared deadlines remain shorter.
- The awaited provider hook locally requests `max_output_tokens` at most 2,000,
  within the phase output reserve. `outputCapApplied` records the local field
  setting; `backendOutputCapQualification: "UNQUALIFIED"` records that backend
  enforcement is unproven. The SDK check observes this field at the synthetic
  SSE fetch boundary. Neither that check nor an offline preflight establishes
  live output-cap enforcement or a future token/spend bound. Optional calibration
  retains its exact request identity and historical usage qualification.
- A semantic rejection aborts at `tool_result`. The isolated SSE transport
  checks the abort before fetch; abort intent alone is not transport evidence.
  The default outcome is `INTENTIONAL_REJECTION`. Optional, explicitly declared
  evidence delivery admits only the exact product-issued `READ_RESULT` request;
  changed cursor/scope/grants, RUN and RESUME are blocked before execution.
- Canonical `qualified` replies enter DELIVERY and preserve known minimum,
  limitations and closed progress. A final interpretation is `QUALIFIED_ANSWER`,
  never exhaustive success. Rejection diagnostics read the actual producer's
  `detail.originalCoverage`, separately from `detail.policyProgress`.
- DELIVERY from a received complete or qualified result permits interpretation but
  no additional tool. `HARNESS_BUDGET_LIMIT` remains a harness outcome even when
  the saved native evidence is complete. It is not model/product failure or
  successful answer delivery.
- Final answer text, native exhaustive coverage and independently verified
  rows/evidence are separate report fields. The controller never claims external
  oracle verification from a model sentence or a returned count.
- Reports distinguish model proposals, harness decisions, adapter starts and
  RPC versus observed native replies. Closed Tool RPC failures preserve their
  exact code as `TOOL_RPC_REJECTION`, not a semantic completeness rejection.
  An adapter-start event alone is not proof of semantic
  execution. No source payloads, credentials, headers or environment are logged
  in the shareable summaries. Guard and event records share one 64-KiB cap,
  including one reserved `RECORDS_TRUNCATED` marker. Only allowlisted stages,
  outcomes, counts and hashes are retained. Record failures remain typed sticky
  failures and abort further work. Exact replay is separate: owned0700 directories
  and0600 files, with scoped umask077. Restored sessions open an owned copy;
  the original transcript is not opened for SDK mutation. The policy report
  and exact replay remain private; summaries are the export surface.
- Each case runs in one owned process group. Cancellation first requests SDK
  abort, then terminates and reaps an unresponsive worker and its descendants.
  Existing IDEs and user sessions are never owned or restarted by this controller.

## Explicit execution plan

`run_pi_evaluation.mjs --plan /absolute/plan.json` only validates the plan and
prints declared budgets. It does not load Pi, authenticate or invoke Kast.
`--execute` is a separate, explicit opt-in and requires new authorization for
live evaluation. Implementation/test authorization alone does not authorize it.

The plan must supply absolute `piPackageRoot` (the installed coding-agent package,
not its binary), `kastAdapter`, `workspaceRoot`, new `outputRoot`, `authPath`, and
`modelsStorePath`; exact `piVersion` and `kastAdapterSha256`; and independent
`cases`. Credentials are accessed with Pi's `ReadOnlyAuthStorage`; they are never
copied into the isolated profile. Existing OAuth validity must cover preparation
without refresh. Settings are in memory, with retry/compaction disabled and no
ambient packages/context files. The actual candidate adapter supplies unchanged
tool schemas. All three read tool declarations remain enabled. The supported
in-memory settings explicitly select `transport: "sse"` and `cacheWarming: "off"`,
and construction rejects a runtime that cannot preserve them. These settings do
not change the user's normal profile. No implicit warming, retry or catalog
refresh may debit an unobserved case allowance.

Each case declares `name`, `mode`, `expectedSemanticCalls`, `inputTokenCeiling`,
`declarationByteCeiling`, `maximumToolTextBytes` and a positive `wallSeconds`.
The applied deadline is the smaller of that value and 120 seconds for fresh
execution or 60 seconds for received-result execution. The parent plan uses a
100,000-byte declaration limit and 300,000-byte tool-text limit. For example,
a dense case can declare:

```json
{
  "work": {"reportedTokens": 120000, "requests": 3, "tools": 2, "outputReserve": 2000},
  "delivery": {"reportedTokens": 20000, "requests": 1, "outputReserve": 2000},
  "maximumReportedTokens": 140000,
  "maximumProviderRequests": 3,
  "maximumProviderPayloadBytes": 262144,
  "maximumProviderResponseBytes": 65536,
  "declarationByteCeiling": 100000,
  "maximumToolTextBytes": 300000,
  "wallSeconds": 120
}
```

Use `mode: "fresh"` with the fixed public question in `prompt`. The parent
plan declares global thresholds of 60,000 reported tokens for the negative case,
140,000 for the dense case and 60,000 for saved-result delivery. These are explicit
plan values, not case-name rules. The prior declaration array measured 73,716
bytes; that establishes a byte measurement only. Keep the declared request,
byte, response and deadline limits. Calibration is optional and cannot turn
post-response thresholds into pre-request token or spend guarantees.

For `mode: "received-result"`, provide the exact `receivedSessionFile` and
`receivedResultEntryId`, the full original file's `receivedSessionSha256`, the
expected restored `receivedContextSha256`, and omit `prompt`. The original must
be a current-user-owned, regular, nonsymlink, single-link file without group or
world write permission. Ordinary Pi `0644` sources and their existing parents
remain unchanged. The source has a separate 4 MiB ceiling; the 256 KiB provider
body ceiling does not limit the original transcript. An `O_NOFOLLOW` opened
descriptor and the pathname must retain the same identity, size and nanosecond
modification metadata throughout the bounded read. Growth, truncation, identity
changes and a full-file digest mismatch fail before creating the replay copy or
opening it through the SDK. Only authenticated bytes are copied to an exclusive
`0600` file in the owned `0700` archive; copy failures remove that owned copy,
and cleanup failure preserves the first finite failure.
The saved workspace and exact restored context are checked before inference.
Public `SessionManager.open` opens a private owned copy, and
`branch` selects the saved tool-result leaf in memory; the next appended entry
persists that branch without replacing older entries. `session.agent.continue()`
consumes it without a new task, trimming, summary, reissued query or coaching.
The original session entries remain intact. DELIVERY blocks all new tool calls.

`test_pi_evaluation_mutations.py` exercises adverse changes to the production
rules. Its executable selectors and expected behaviors remain authoritative.
A syntax/import or provisioning failure does not count as a killed mutation.

## Optional full-request calibration and no-inference preflight

A case may supply at most64 `inputCalibrations`, each an exact object:

```json
{"type":"OFFLINE_FULL_PAYLOAD_CALIBRATION","path":"/private/receipts/request.json","sha256":"<64 lowercase hex characters>"}
```

Receipt files must be owned regular files, with one link, mode0400 or0600, inside
an owned0700 directory. Symlinks, identity changes, extra reference fields and
files above4MiB are rejected. Descriptors close before verification/registration.
The worker loads and verifies all references before creating the SDK session.
It records finite `INPUT_CALIBRATION` preparation/failure evidence without paths
or request content. Receipt authority is explicit:

```json
{
  "type":"FULL_PAYLOAD_CALIBRATION",
  "provider":"openai-codex",
  "model":"gpt-6.1-sol",
  "request":{"model":"gpt-6.1-sol","instructions":"fixed","input":[],"tools":[],"reasoning":{"effort":"high"},"max_output_tokens":2000},
  "usage":{"input":100,"cacheRead":0,"cacheWrite":0,"output":10,"totalTokens":110},
  "calibratedInputCeiling":100,
  "method":"EXACT_PROJECTED_REQUEST_FINALIZED_USAGE",
  "qualification":"CALIBRATION_NOT_TOKENIZER_PROOF"
}
```

This layout is illustrative; request and usage must come from the exact qualified
full-request observation. Do not use these sample values as live calibration.
The request identity omits only store, stream, cache key and locally requested output cap.
Source authenticity and backend stability remain external provenance obligations.
A synthetic finalized-usage receipt is test evidence only. Existing receipts
for instructions alone do not calibrate a new fresh or restored full request.
A novel request can be admitted without any receipt under the exact serialized
byte cap and finite request count. Changed dynamic call IDs, tool results and
reasoning items need no fabricated calibration. Optional matching receipts
supply empirical diagnostics; their absence or mismatch does not establish a
failure or a complete-input token bound.

The installed Pi Codex provider exposes an awaited full-payload hook. This
controller uses it to inspect the complete request and apply the local output
cap. It does not require an input-count endpoint or tokenizer. Backend output
cap enforcement remains `UNQUALIFIED`. Live evaluation still requires explicit
authorization and matched installation/native admission; its token thresholds
are post-response stops, and one response can overshoot them.

The preflight recipe below uses private digest-pinned captures of each complete
SDK body. It loads no SDK, credentials, model catalog or native service and sends
no request. The manifest selects each case and WORK or DELIVERY grant explicitly.
Admission is independent per request; the receipt says so and cannot establish
sequence accounting, same-session restoration or live output-cap acceptance.

```sh
node experiments/host-observation/run_pi_evaluation.mjs --plan /private/plan.json
node experiments/host-observation/pi_evaluation_preflight.mjs \
  --plan /private/plan.json --manifest /private/requests/manifest.json \
  --sha256 "$MANIFEST_SHA256"
```

The private manifest binds the private plan digest and at most64 unique
case/phase entries. It must cover WORK for each fresh case and DELIVERY for each
received-result case; fresh DELIVERY can be checked independently as well:

```json
{
  "type":"FULL_PROVIDER_REQUEST_PREFLIGHT",
  "planSha256":"<plan SHA256>",
  "requests":[
    {"case":"fresh-control","phase":"WORK","body":{"type":"FULL_PROVIDER_PAYLOAD","path":"/private/fresh-body.json","sha256":"<body SHA256>"}},
    {"case":"received-control","phase":"DELIVERY","body":{"type":"FULL_PROVIDER_PAYLOAD","path":"/private/received-body.json","sha256":"<body SHA256>"}}
  ]
}
```

Plan, manifest and body files use the same private ownership/mode/size boundary
as receipts. Full body files must already contain the locally requested output cap;
preflight never adds fields, trims context or rewrites declarations. The report
retains hashes and finite admissions only. A declared received-result identity
is validated by the plan parser; actual session restoration remains a later SDK
check. Structural parsing is bounded at depth64 and32768 JSON nodes, with at
most1024 input items and64 tools. The installed declaration schema requires
depth33; the larger finite depth supports that complete schema and its receipt
without changing any token or semantic-work allowance.

Changed declarations, instructions, input or restored `additional_tools` change
the recorded request digest. A novel bounded supported request remains eligible
without calibration; unsupported or oversized payloads deny before transport.
The report records exact whole-file and input-projection hashes, applied request
and response caps, deadline, global reported-token threshold and local requested/
applied output settings. It retains `UNQUALIFIED` backend enforcement and
`INDEPENDENT_REQUEST_ADMISSION_NOT_SEQUENCE_OR_LIVE_PROOF`. This check cannot
establish sequence accounting, actual restored-session identity, live backend
behavior, token usage or spend. No provider request is sent by preflight.

`inputEstimate`, `inputEstimateMethod` and `inputEstimateQualification` preserve
the diagnostic estimate and its uncertainty. `calibratedInputCeiling` contains a
value only for an actual matching empirical calibration; it is null for a novel body.
The one-token-per-byte heuristic and `required` estimate are not tokenizer proof,
a guaranteed upper bound, a spend guarantee or an admission decision.

## Installed SDK check without inference

`pi_evaluation_sdk_check.mjs` is a separate opt-in installed-dependency check;
CI policy tests do not require a personal Pi install. Supply the actual installed
coding-agent package root, release adapter path and admitted adapter digest:

```sh
node experiments/host-observation/pi_evaluation_sdk_check.mjs \
  "$PI_PACKAGE_ROOT" "$KAST_ADAPTER" "$KAST_ADAPTER_SHA256"
```

This loads the actual adapter's read-only catalog. It uses the committed worker's
SDK construction and awaited hooks. Only external provider transports are doubles. Its auth and catalog storage are synthetic and in memory. All
provider responses are synthetic; zero inference, semantic tools and real
provider requests occur. A first synthetic SSE response completes; a denied
second request reaches neither fetch nor a socket. A separate real-SDK cached
WebSocket session reproduces one send after its guard denied the second request.
A fresh sequence also uses the actual installed SDK to project a new tool call
and complete canonical result into its second request. Only tool execution is
replaced with a synthetic reply; declarations and instructions remain intact.
Both requests admit without a prior calibration and end in `FINAL_ANSWER`.
This is SDK projection and guard proof, not native or backend enforcement proof.
A saved transcript then continues through the same SDK without a new user prompt
or semantic call. Its tool text appears once; duplicate `details` fields stay
out of the provider request. The test checks budget admission and the 2,000-token
request cap. These checks validate projection and transport, not native semantics
or live backend support for the cap.

The checked coding-agent package is 1.0.2, but its resolved pi-ai and
pi-agent-core dependencies are both **1.1.0**. Sanitized exact source/package
digests and startup settings are in `pi-fixtures/installed-sdk-evidence.json`.
Relevant installed source is `pi-ai/dist/api/openai-codex-responses.js`:
the awaited payload hook at 174, SSE's abort check at 260 before fetch at 270,
cached connection reuse at 900, and socket send at 1204. Pi's
`dist/core/settings-manager.js` defaults cache warming to streaming at 679–682;
the worker explicitly overrides it. No claim is made that the original pilot
actually ran a cache-warming request. SDK `tools: string[]` is the supported
public contract. Fixture provenance and offline owning Kotlin serialization
steps are in `pi-fixtures/README.md`.

## Terminal report delivery

The parent pins one overall deadline before spawning the owned worker and
passes it through the owned process environment. Startup and terminal publication
consume that same bound. The worker publishes once and waits for its callback
only within the parent's remaining time. Backpressure is not delivery failure.
An independent one-second send allowance is not used. After settlement the
worker disconnects and exits naturally, without truncation or retry. A closed
channel, send failure or deadline expiry is finite data.

The controller seals the first accepted terminal report or failure in an immutable
receipt. A complete report received before cancellation and deadline expiry
cannot be replaced by a later report, malformed frame, worker failure or cleanup
error. A failure established before that report remains primary. `cleanupFailure`
retains one finite secondary diagnostic; `ipcCleanupFailure` separately records
a reserved IPC exit failure. Neither can change the sealed outcome or its receipt
authority. A missing terminal message remains subject to finite overall
cancellation and owned-child reaping. Report/worker-failure
receipt and child exit are separate observations. Neither claims an unseen child
callback. A nonzero child exit still gives the CLI a failure status; the private
aggregate retains the received report and cleanup observation.

Real owned-child tests include a delayed1,200ms callback within the overall5s
bound, charged startup, never-completing publication and sticky semantic failure.
Deterministic ordering cases cover cancellation-send, observer and process-kill
errors, duplicate and conflicting reports, and malformed or failed later frames.
They preserve exact300k/1MB reports and a policy-accepted
synthetic rejection summary whose limitations fit the300,000-byte tool allowance.
The latter establishes accepted-shape transport reliability; it does not prove
that a native producer or normal pilot creates14,000 repeated limitations.
Callback-error and timeout cases retain finite failures and reap owned children.

## Minimal next live validation (not run by this change)

After explicit authorization and matched installation/native admission, run one
fixed public 1001-reference case and the exact-negative control against the same
frozen fixture/oracle, with independent counters. Default dense rejection stops
at its first typed reply. Negative control gets its declared final allowance.
If a saved result needs delivery, authorize one received-result continuation,
retain the exact transcript and prove zero additional semantic calls. Compare
rows and qualification with an external oracle; label contention. No semantic
retry, schema reduction, source mutation, new login or global setting edit is
part of that plan.

## #995 query delivery integration

The classifier also admits the actual `query_delivery` envelope at reviewed
client head `36a2e6d256d620cf37a4e7e622ee643d4c38c178`. Raw canonical
adapter replies remain compatible. The harness observes one logical result;
it never implements paging, advances cursors or invokes the shared client again.

`delivery.stop=DELIVERED` proves output delivery only. Initial qualification and
rejection remain authoritative even when a suffix is complete. All canonical
inner qualifications/rejections remain unchanged in the model-visible result
and private transcript; reports retain structured summaries of each outcome.
Initial rejected proof remains an intentional rejection. The explicitly enabled
evidence-only interpretation mode may interpret already delivered proof under
its bounded DELIVERY allowance, but cannot read that proof again.

By default, non-DELIVERED statuses are `CLIENT_DELIVERY_BLOCKED` with the exact client stop,
including unavailable delivery, required budget increase and cancellation. No
RUN, RESUME, retained read or provider continuation can recover that blocked
case. A BYTE_LIMIT envelope with a null initial reply preserves original_outcome
as a hint, without inventing native coverage. No remote cancellation settlement
is inferred from the client's CANCELLED observation.

### Explicit received-prefix interpretation

`evidenceOnlyDelivery: true` also permits one DELIVERY answer when `BYTE_LIMIT`
leaves an intact retained `COMPLETION_UNPROVEN` rejection and a consistent,
nonempty prefix of exact-symbol or reference-occurrence rows. All received pages
must retain the original question, result identity, coverage interpretation and
live observation. Row cursors and evidence windows must be contiguous. Unknown
rows, repeated or conflicting row identities, missing authority, stale/expired rejection,
retention loss, cancellation and other client stops remain blocked. The false
policy keeps its existing rejection behavior.

The report's nullable `partialEvidence` records unique received rows, duplicate
preview rows, received/total evidence entries, original known minimum, exact
question, observed live authority, result identity and one-answer limit. Row
identity preserves distinct occurrences even when they share a target selector.
The retained preview must match the ordered received prefix, including issued
row IDs; conflicting or uncovered previews fail this narrow opt-in. Legacy rows
with absent or null IDs use exact symbol identity or the occurrence's target, meaning, site,
context, ownership and provenance. Descriptions never invent another identity.
Any repeated received identity is rejected, even when its description matches.
Only the preview is a permitted copy of already received positions.
Canonical documents, all qualifications, failure entries and byte/RPC counts
remain unchanged. A native minimum is not an exhaustive total.

The actual guard appends a bounded interpretation instruction before measuring
the complete provider body. It requires the answer to state the native rejection,
client stop, received facts, missing evidence, exact scope and observed authority.
This instruction guides an answer; it does not verify prose correctness or fresh
authority. The policy permits no further tool, retained read, RUN or RESUME.
Provider bytes, response bytes, output reserve, cumulative usage and wall bounds
still apply. Saved continuation keeps its pinned context with no new user prompt.
A final answer retains `INTENTIONAL_REJECTION` and `nativeOutcome: REJECTED`;
external row/evidence verification remains false until separately established.

Decision: constrain this exception to byte-limited rejection evidence. Other
partial outcome families and resource stops require separate counterexamples.
The harness changes no production client, generated schema owner, native retention,
semantic budget, transport limit, adapter presentation or installation.

Launch-free evidence from the sealed dense envelope, inspected without replaying
its authority handles:

| Observation | Before | After |
|---|---|---|
| True-policy saved prefix | `CLIENT_DELIVERY_BLOCKED` | One bounded DELIVERY answer eligible |
| False-policy saved prefix | `INTENTIONAL_REJECTION` | Unchanged |
| Native result | Rejected; minimum 194 | Unchanged |
| Received unique / duplicate preview rows | 100 / 17 | 100 / 17 |
| Received / total evidence entries | 0 / 3 | 0 / 3 |
| Canonical UTF-8 bytes | 227,363 | 227,363 |
| New semantic work / transport RPCs / provider sends | 0 / 0 / 0 | 0 / 0 / 0 |

The independent fixture expects 1,001 rows; that total is external oracle data,
not inferred from the native minimum or received prefix. The received scope is
`main,test`, production-and-test, direct containment of
`src/main/kotlin/reliability`. It differs from the earlier control's scope and
cannot establish a controlled speed result. A synthetic complete provider body
containing only this received canonical result and the new instruction measures
247,261 bytes. It fits the existing 262,144-byte cap, but does not establish that
the historical SDK context fits, or that any future request is admitted.

Validation starts with the named failing policy case, then exercises production
policy and guard callbacks for false-policy controls, malformed/stale evidence,
scope/authority changes, duplicate identities, multi-page evidence counts, first
terminal result preservation, complete-body byte rejection and the one-request
limit. The existing Node suites run without inference or IDEA.
Independent review and installed combined qualification remain separate gates.

Review hardening admits the complete rejection and every qualified page against
the existing `installedSemanticResultSchema(QUERY_RUN)`. Its actual canonical
serializers and local output definitions cover rows, nested evidence, budgets,
recovery and authority. No handwritten row or rejection schema is added.
JVM parity prevents snapshot drift; an independent JSON Schema oracle compares
the pure Node admission on canonical documents and recursive type/unknown-field
mutations. Existing finite schema depth and provider bounds remain unchanged.
Schema admission does not restore compiler or current liveness authority.

Cross-field checks bind recovery output to the original question, row families to
that output, and full page progress to the returned evidence groups. Terminal
reason must match evidence-read qualification; a terminal page cannot issue a
continuation or return new rows after its final row cursor. Canonical omitted or
null row IDs/cursors retain their owning DTO meaning. The synthetic prefix uses
native `TERMINAL_INCOMPLETE` separately from client `BYTE_LIMIT`.
Evidence-read qualification preserves the
original minimum and limitations, adds `static-model-unproven`, and uses
`upstream-incomplete` progress, as `completionEvidenceRead` specifies. Retained
selection may additionally add `result-limit-reached`, as
`RetainedQueryPresentation.pageCoverage` specifies. Qualification does not copy
the original producer progress reason. Unknown enums, missing limitations and
contradictory qualifications remain blocked.

`queryDeliveries` records stop, physical rpc_count and UTF-8 byte counts separately
from logical model proposals and semantic replies. A failed RPC can increase
rpc_count without adding a page. Saved-result continuation reports historical
delivery RPC counts separately and executes zero new semantic calls. Pi's
observed isError flag and the client's expected host failure remain distinct
from semantic completion; host success is not proof of exhaustive evidence.

The twelve envelope fixtures are produced by the actual pinned shared client,
using #995's Kotlin-serialized and schema-admitted canonical fixture owner.
`generate_pi_delivery_fixtures.mjs` asserts both input/source digests and calls
that client's original functions through the same export-only exposure used by
its production tests. Only external transport is doubled. Reproducibility and
class/source hashes are in `pi-fixtures/README.md`. These 43-row contract fixtures
are not a replacement for the frozen public 1001-reference live scenario. No
model, native query, production adapter edit or activation was performed.
