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
node --test experiments/host-observation/pi_evaluation.test.mjs
python3 -m unittest discover -s experiments/host-observation -p test_pi_evaluation.py
```

The actual SDK worker uses the same exported guard and rules tested here. Node
is required; a missing interpreter is a test provisioning failure, never a skip
or GREEN. The Python wrapper includes these checks in the existing Gradle gate.

## Rules and evidence

- Every named case owns fresh counters and explicit WORK and DELIVERY budgets.
  Work cannot debit another case or its separate final interpretation allowance.
- Provider preflight proves the exact `openai-codex/gpt-6.1-sol` selection and
  effective `high` effort, and records serialized declaration, instruction,
  context and whole-request byte sizes. Admission takes the greater of the
  initial input calibration plus conservative context growth and the latest
  measured input (including cached input) plus subsequent growth, then adds the
  output reserve. These are estimates; bytes are not tokenizer counts.
- Finalized API usage keeps uncached input, cached input read/write, generated
  output and reasoning subset separate. Total usage includes cached input on
  each request. Reasoning is not an extra additive category. There is no claim
  of a provider-enforced output/token cap; an in-flight overshoot is explicitly
  `HARNESS_BUDGET_LIMIT` and stops further requests.
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
  observed native replies. An adapter-start event alone is not proof of semantic
  execution. No source payloads, credentials, headers or environment are logged
  in the shareable guard report. Session/event logs are private local artifacts.
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
`declarationByteCeiling`, `maximumToolTextBytes`, `wallSeconds` (1–480), and:

```json
{
  "work": {"reportedTokens": 60000, "requests": 3, "tools": 2, "outputReserve": 2000},
  "delivery": {"reportedTokens": 25000, "requests": 1, "outputReserve": 2000}
}
```

Use `mode: "fresh"` with the fixed public question in `prompt`. A negative case
can declare one semantic call, WORK 30,000, and independent DELIVERY 25,000.
The previous measured declaration array was 73,716 bytes; a declared 80,000-byte
ceiling and 20,000-token initial input ceiling leave explicit space for that
context. Validate new schema/context measurements before selecting bounds.

For `mode: "received-result"`, provide the exact `receivedSessionFile` and
`receivedResultEntryId`, the expected `receivedContextSha256`, and omit `prompt`.
The saved workspace and exact restored context are checked before inference.
Public `SessionManager.open` and
`branch` select the saved tool-result leaf in memory; the next appended entry
persists that branch without replacing older entries. `session.agent.continue()`
consumes it without a new task, trimming, summary, reissued query or coaching.
The original session entries remain intact. DELIVERY blocks all new tool calls.

`test_pi_evaluation_mutations.py` kills eleven changes to the real rules: removing
the terminal gate, sharing the final budget, dropping cached-input accounting,
allowing semantic work in DELIVERY, weakening exact evidence-read admission,
adding a coaching prompt, rejecting valid qualified replies, losing original
coverage, ignoring measured input, selecting unsafe automatic transport, and
enabling unaccounted warming. A syntax/import
or provisioning failure does not count as a killed mutation.

## Installed SDK check without inference

`pi_evaluation_sdk_check.mjs` is a separate opt-in installed-dependency check;
CI policy tests do not require a personal Pi install. Supply the actual installed
coding-agent package root, release adapter path and admitted adapter digest:

```sh
node experiments/host-observation/pi_evaluation_sdk_check.mjs \
  "$PI_PACKAGE_ROOT" "$KAST_ADAPTER" "$KAST_ADAPTER_SHA256"
```

This loads the actual adapter's read-only catalog, uses the committed worker's
real SDK construction and awaited hooks, and doubles only external provider
transports. Its auth and catalog storage are synthetic and in memory. Both
provider responses are synthetic; zero inference, semantic tools and real
provider requests occur. A first synthetic SSE response completes; a denied
second request reaches neither fetch nor a socket. A separate real-SDK cached
WebSocket session reproduces one send after its guard denied the second request.
This validates the selected transport boundary; it is not native semantic proof.

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
