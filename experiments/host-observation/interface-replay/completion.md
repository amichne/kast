# Interface replay delivery

The corrected first scope now uses case version 3.
The [projection and state review](projection-review.md) records a preceding-command mismatch and the corrected inputs.
The current bounded screen remains `NOT_RUN` until the required offline gate passes.
Earlier acceptance and performance reports remain historical development evidence.
The later [hosted review repairs](hosted-review.md) add controls for command facts and tool proposals without changes to recorded verdicts.
Codex credentials supplied actual provider access through the existing Pi SDK runner.
The later 3× target remains `NOT_MET`. The tested candidates supply no 3× token or latency claim.
The local Codex credential check succeeds. Current provider access remains unproved until the version-3 screen.

## Acceptance evidence

| Criterion | Status | Evidence |
| --- | --- | --- |
| A1 | PENDING | Source commits, case digests, SDK digests, model settings, adapter artifacts, and plans appear in the versioned manifests. |
| A2 | PASS | Six independent graders accept correct controls and reject changes to syntax, target, scope, reference, and coverage. |
| A3 | PASS | The production renderer and independent decoder preserve complete canonical values, exact strings, arrays, and large integers. |
| A4 | PASS | Real restricted Bash reaches production parsing, lowering, input decoding, and admission. Raw commands and effects remain unchanged. |
| A5 | PASS | The replay path starts no Host, MCP connection, or native semantic call. |
| A6 | PASS | Deterministic pool and child tests cover isolation, request limits, cancellation, report delivery, and child cleanup. |
| A7 | PENDING | Each measured screen admitted 24 complete SDK inputs before inference. Each actual payload contained the intended result exactly once. |
| A8 | PASS | Every planned trial appears in its report. Failed work, cache tokens, missing usage, and excluded trials preserve separate meanings. |
| A9 | NOT_RUN | Four declared screens produced 96 actual sends. Each screen kept six cases, two formats, two repetitions, and eight workers. |
| A10 | PENDING | Focused Kotlin, Node, real-shell, SDK, contract, graph, knowledge, prose, and fast repository guards passed. |

The original offline report remains historical evidence of expired Pi access and a decoder failure.
The [first live screen](first-live.md) preserves the original decisions and strict grades.
The [clarified baseline](baseline-v2.md) declares exact limitation codes before its requests.
The baseline fixes TOON quality at twelve correct decisions. No retrospective regrade creates an improvement.

## Measured candidates

| Run and format | Correct | Provider tokens | Median trial duration |
| --- | --- | --- | --- |
| Baseline TOON | 12/12 | 11,669 | 7.93 seconds |
| Baseline JSON | 11/12 | 11,107 | 6.59 seconds |
| Session TOON | 12/12 | 11,712 | 4.81 seconds |
| Session JSON | 12/12 | 11,134 | 4.92 seconds |
| Concise TOON | 10/12 | 11,549 | 8.09 seconds |
| Concise JSON | 12/12 | 11,051 | 7.04 seconds |

The session profile changes local execution only. Its model-visible inputs match the baseline exactly.
The [TOON assessment](session-toon-target.json) records a 1.537× paired latency ratio and a 0.996× token ratio.
These ratios use complete tokens per correct decision and the median of six per-case paired latency ratios.
They do not equal the ratio between the table's cohort medians.
The [JSON assessment](session-json-target.json) also rejects the 3× target.

The concise profile changes one global instruction contract and preserves every task and canonical result.
It saves 32 input tokens per trial. More output tokens offset most of that decrease.
The [TOON assessment](concise-toon-target.json) rejects its accuracy regression.
The [JSON assessment](concise-json-target.json) records only a 1.008× token ratio and a 0.766× paired latency ratio.
The concise profile supplies no accepted improvement.
No failed trial received a hidden retry. No separate calibration or cache-warming request occurred.

The [campaign ledger](campaign-ledger.json) includes all four screens, including the first contract with missing limitation-code instructions and every model failure.
It records 96 actual sends, 90,596 provider tokens, and 88 correct decisions among 96 eligible decisions.
These totals describe development work. They do not turn repeated tasks into independent samples.
Each measured screen has complete usage, no excluded invalid trials, no missing pairs, and zero native calls.
Provider tokens measure work. Billed cost remains unavailable.

## Source and artifacts

The baseline source is `e6b08bd8b8a840c74602104a54e6f713fa854557`.
Both candidate screens use clean source `2a29147498efc94fc848aa441191472d0167b101` with the same pinned model, effort, and Pi 1.4.0 SDK files.
The [session manifest](session-manifest.json) and [concise manifest](concise-manifest.json) record full source, artifact, case, and plan digests.
The [baseline derivation](baseline-v2-derivation.json) binds added comparison metadata to the exact original source and receipt hashes.
It adds the known `full` and `fresh` profiles and the source-bound public schema digest.
It changes no original decision, grade, payload, token count, or duration.

The existing `pi_evaluation*` owners contain planning, scheduling, sessions, instrumentation, accounting, graders, and the restricted shell.
`PublicReplayAdapter` remains test-only and calls the production owners.
Its optional JSON-line mode owns one JVM per trial, one admission, and one render.
It rejects excess requests, invalid UTF-8, and oversized frames with finite failures.
The parent stops and reaps its child after success, failure, or cancellation.

Production still defaults to TOON and honors semantic `--json`.
The independent decoder uses published `Ktoon` 6.0.0. The production renderer still uses `JToon` 3.0.0.
The decoder change repairs the experiment's equality proof without a product migration or data repair.

## Exact commands and statuses

All paths in these commands are relative to the task checkout unless `$PWD` supplies an absolute path.

```sh
mise exec -- ./gradlew :cli:prepareReplayAdapter --console=plain
node --test experiments/host-observation/pi_evaluation_replay*.test.mjs
KAST_REPLAY_TEST_PLAN="$PWD/build/interface-session-test-plan.json" KAST_REPLAY_ADAPTER_MANIFEST="$PWD/cli/build/replay-adapter/manifest.json" node --test experiments/host-observation/pi_evaluation_replay_sdk.test.mjs experiments/host-observation/pi_evaluation_replay_adapter.test.mjs experiments/host-observation/pi_evaluation_replay_adapter_session.test.mjs experiments/host-observation/pi_evaluation_replay_codex_sdk.test.mjs
mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*' :cli:spotlessCheck :cli:checkTestKotlinFileLength verifyJsonContracts verifyVerificationGraph knowledgeImpact verifyKnowledgeBase hostObservationTest --console=plain
mise exec -- uv run --locked --script .github/scripts/ci/fast_checks.py
node experiments/host-observation/run_pi_evaluation.mjs --prepare-replay "$PWD/build/interface-codex-session-v2.json" --auth=codex --execution=session
node experiments/host-observation/run_pi_evaluation.mjs --plan "$PWD/build/interface-codex-session-v2.json"
node experiments/host-observation/run_pi_evaluation.mjs --execute "$PWD/build/interface-codex-session-v2.json"
node experiments/host-observation/run_pi_evaluation.mjs --prepare-replay "$PWD/build/interface-codex-concise-v2.json" --auth=codex --execution=session --instructions=concise
node experiments/host-observation/run_pi_evaluation.mjs --plan "$PWD/build/interface-codex-concise-v2.json"
node experiments/host-observation/run_pi_evaluation.mjs --execute "$PWD/build/interface-codex-concise-v2.json"
node experiments/host-observation/run_pi_evaluation.mjs --assess-target "$PWD/build/interface-session-toon-target-input.json"
node experiments/host-observation/run_pi_evaluation.mjs --assess-target "$PWD/build/interface-concise-toon-target-input.json"
```

- **PASSED:** 91 public-shell Kotlin tests, including 15 focused adapter tests, with zero failures.
- **PASSED:** 84 deterministic replay Node tests. Ten optional integration tests skipped without their explicit environment variables.
- **PASSED:** All 27 opt-in real-JVM, restricted-shell, SDK, credential, parity, and cancellation tests, with zero skips.
- **PASSED:** 332 host-experiment Python tests and the 23 portable fast-check suites, with zero platform skips.
- **PASSED:** JSON contracts, graph fingerprints, knowledge impact, knowledge citations, formatting, and file-length guards.
- **PASSED:** Scoped glossary and prose guards. Pinned Vale and Repository Knowledge reports remain under `build/`.
- **ASSESSED:** Prose retains grade-8 advisories for exact technical terms. This status supplies no reader-comprehension proof.
- **NOT_MET:** The target commands exit with status 1 because the measured candidates miss the declared goals.
- **NOT RUN:** Native acceptance, compiler semantic fixtures, publication, deployment, and a broader model sweep.
- **NOT RUN at the initial local handoff:** The full pre-push product gate. The later delivery review records the separately authorized merge work.

Private payloads, raw shell records, actual `argv`, `stdin`, `stdout`, `stderr`, exit statuses, and immutable receipts remain in each recorded `build/` run.
The original first-screen and baseline commands also remain in their source-bound reports and plans.
Run directories cannot overwrite an earlier result. A repeated screen needs a new plan and output root.

## Smallest failure reproductions

The original independent `JToon` decoder misreads this production TOON result:

```text
items[1]:
  - connections: []
```

It returns the string `"[]"` instead of an empty array. The current independent decoder preserves the array.
The corpus also preserves a 40-digit integer without JavaScript numeric conversion.

The concise screen preserved these two malformed model commands:

```sh
kast source read --ref exact:v5:AAAAAAAAAAAAAAAAAAAAAg --region callable-body --text complete potable
kast result read --ref result:v1:00000000-0000-0000-0000-000000000004 --cursor 1 w
```

Production admission rejects each as `INVALID_COMMAND`. The experiment removes neither trailing argument.
The [baseline report](baseline-v2.md) preserves its separate trailing-`timeout` failure.
The [first live report](first-live.md) preserves the incomplete limitation-code instructions and its original grades.

## Remaining limits

The implementation remains available for offline qualification. Version 3 needs its bounded screen. The 3× goal is not complete.
The measured candidates supply no general performance evidence outside these six fixed synthetic states.
The session candidate improves local replay latency. It supplies no faster native semantic query claim.
The concise candidate loses accuracy and supplies no accepted improvement.
Both encoding arms preserve every original fact, so neither comparison justifies discarding rows, qualifications, references, or source text.
The experiment changes no product default and implements no later interface experiment.

The measured baseline TOON requests consumed 11,120 input tokens before their 549 output tokens.
Even zero output on those unchanged requests exceeds the 3× threshold of 3,889.67 tokens for twelve correct decisions.
This fact concerns the measured requests. It does not prove a universal limit on token counts.
The local execution change preserves that input cost. The shorter instructions save only 32 input tokens per trial.
A later field-selection or context experiment needs a separate scope because the original request explicitly excludes those interface experiments.

## Remaining execution cost

The [execution-cost audit](execution-cost-audit.json) examines the remaining local work without another provider request.
It binds each calculation to the original baseline and session receipt hashes.
The calculation holds the recorded provider durations fixed and sets all candidate local costs to zero.
Under those conditions, the median of six per-case paired ratios reaches only 2.758×.
This arithmetic supplies no measured speedup or future latency limit.
It shows that local work alone cannot reach 3× on the recorded sample with those fixed provider durations.

The unchanged baseline requests also consume 11,120 input tokens before their outputs.
The target permits only 3,889.67 total tokens for twelve correct decisions.
Another local execution change cannot reduce the input tokens of an identical provider payload.
The pending scope question concerns a separate result-projection experiment.
The first scope supplies no authorization to discard task evidence or implement that later factor.
