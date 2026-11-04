# Interface replay delivery

The required first scope passes A1–A10 on the corrected version-3 replay state.
The [current screen](screen-v3.md) records 24 actual sends, complete usage, twelve pairs, and zero native semantic calls.
TOON scored 11 of 12. JSON scored 12 of 12.
The separate 3× token and latency goal remains `NOT_MET`.
The screen selects no general format winner and changes no product default.

## Acceptance evidence

| Criterion | Status | Evidence |
| --- | --- | --- |
| A1 | PASS | The clean source, actual artifacts, SDK files, model settings, cases, preceding commands, and receipt hashes appear in the [manifest](screen-v3-manifest.json). |
| A2 | PASS | Six independent graders accept correct controls and reject changes to syntax, action, target, scope, reference, and coverage. |
| A3 | PASS | The production renderer and independent decoder preserve canonical values, exact strings, arrays, and large integers. |
| A4 | PASS | Restricted Bash reaches production parsing, lowering, input decoding, and public admission. Raw commands and effects stay unchanged. |
| A5 | PASS | The replay path starts no Host, MCP connection, or native semantic call. All 24 measured native-call counts equal zero. |
| A6 | PASS | Deterministic pool and child tests cover isolation, request limits, cancellation, report delivery, and child cleanup. |
| A7 | PASS | All 24 complete SDK inputs passed before inference. Each actual provider payload contained the intended result exactly once. |
| A8 | PASS | Every trial appears in the [comparison](screen-v3-comparison.json). Failed work, cache tokens, missing usage, and invalid trials keep separate meanings. |
| A9 | PASS | The screen used six cases, two formats, two repetitions, eight workers, and 24 actual provider sends. |
| A10 | PASS | Focused Kotlin, Node, restricted-shell, SDK, contract, graph, knowledge, prose, and full pre-push checks passed before inference. |

No live acceptance criterion remains blocked.
The current screen excludes no invalid trial and misses no usage record or pair.
The incorrect command remains unsuccessful in the correctness denominator.
Repetitions cover six tasks and do not supply twelve independent tasks.
The final delivery still needs exact-head hosted checks, the authorized merge, and the requested development release.

## Implementation and protected facts

The existing `experiments/host-observation/pi_evaluation*` owners contain the replay plan, pool, sessions, instrumentation, accounting, graders, and restricted shell.
Fresh and received-result modes keep their original meanings.
Replay composes no Host, MCP connection, or native semantic executor.
Each trial owns its session, fixture, files, counters, and output.
The controller alone writes aggregate reports.

`PublicReplayAdapter` stays in the CLI test source set and calls the production owners.
The driver counts each Kast entry before request decoding or production parsing.
It blocks further entries and production executables.
The shell keeps the raw command, actual `argv`, `stdin`, `stdout`, `stderr`, and exit status.
The runner repairs no model output.

The [hosted review repairs](hosted-review.md) preserve execution intent, fixture-workspace selection, and blocked SDK proposals.
The [projection review](projection-review.md) preserves caller scope, compatible retained output, and production text defaults.
It also records a mismatch between captured signatures and preceding commands before the version-3 input repair.
C1–C5 now explicitly request their three captured fields.
Every original task and canonical value stays unchanged.
A state digest blocks claims across different preceding calls.

Production still defaults to TOON and honors semantic `--json`.
The production renderer still uses `JToon` 3.0.0.
The independent test decoder uses published `Ktoon` 6.0.0.
This decoder change repairs the equality proof without a product migration or data repair.

## Current comparison

| Measure | TOON | JSON |
| --- | --- | --- |
| Correct decisions | 11/12 | 12/12 |
| Provider tokens | 11,777 | 11,229 |
| Tokens per correct decision | 1,070.64 | 935.75 |
| Median trial duration | 12.22 seconds | 11.39 seconds |
| Actual provider sends | 12 | 12 |
| Native semantic calls | 0 | 0 |

Paired correctness has one JSON win and eleven ties.
Eleven pairs contain two correct decisions. Their median JSON-minus-TOON token difference is −53 tokens.
The median paired duration difference is −1.10 seconds across twelve pairs.
The [target assessment](screen-v3-target.json) records 1.144× fewer tokens per correct decision and a 1.140× paired latency ratio for JSON.
The latency ratio uses the median of six per-case median paired ratios.
It does not equal the ratio between the table's cohort medians.
Neither measure reaches 3×. Billed cost remains unavailable.

The [campaign ledger](campaign-ledger.json) counts 120 actual sends and 113,602 provider tokens across all five development screens.
Historical version-1 and version-2 screens keep their original 96 receipts, 90,596 tokens, and 88 correct labels.
Their preceding-command mismatch prevents current acceptance or performance claims from those records.
The current cohort contains only the 24 version-3 trials.
No failed trial received a hidden retry or separate calibration request.

## Source and artifacts

The tested implementation source is `eaa4efbb528be4652409f2de32915e67ed8826a0`.
The delivery integrates current main `7d4d88f6aedd88aa4f19e8ae4d5cde38319e952c` without changes to its native release-catalog repair.
That repair changes management HTTPS transport and native-image support. Replay inputs, rendering, parsing, and admission remain unchanged.
The working-tree digest is `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945` for the clean tree.
The adapter aggregate digest is `4b5083255f505afc2682a6c69732c35c130249f07429cd2def97370eeab3fd4d`.
The plan digest is `636180077f6a7885041230bdc8555db4b1c9a86a389efd5c7b14d1c287cb1a8c`.
The [manifest](screen-v3-manifest.json) records every case digest and all pinned SDK file hashes.
The model stays `openai-codex/gpt-6.1-sol`, with high effort through Pi 1.4.0.
Codex ChatGPT access works through the existing Pi provider route and session owner.
The runner changes no credential file and starts no separate Codex agent loop.

Private payloads, complete SDK inputs, raw shell records, and immutable receipts remain under `build/interface-codex-screen-v3.json.run/`.
The adapter manifest lists actual artifact paths and hashes under `cli/build/replay-adapter/manifest.json`.
Run directories cannot overwrite earlier evidence.
Historical reports remain linked through the [README](README.md), with their source identities and original verdicts.

## Exact commands and statuses

```sh
node --test experiments/host-observation/pi_evaluation_replay*.test.mjs
KAST_REPLAY_TEST_PLAN="$PWD/build/interface-session-test-plan.json" KAST_REPLAY_ADAPTER_MANIFEST="$PWD/cli/build/replay-adapter/manifest.json" node --test experiments/host-observation/pi_evaluation_replay_sdk.test.mjs experiments/host-observation/pi_evaluation_replay_adapter.test.mjs experiments/host-observation/pi_evaluation_replay_adapter_session.test.mjs experiments/host-observation/pi_evaluation_replay_codex_sdk.test.mjs
GRADLE_USER_HOME="$PWD/build/interface-gradle-home" mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*' :cli:detekt :cli:spotlessCheck :cli:checkTestKotlinFileLength verifyJsonContracts --console=plain
GRADLE_USER_HOME="$PWD/build/interface-gradle-home" git -c 'core.sshCommand=ssh -o ServerAliveInterval=20 -o ServerAliveCountMax=30' push origin codex/interface-replay-evaluation
GRADLE_USER_HOME="$PWD/build/interface-gradle-home" mise exec -- ./gradlew -Pversion=0.50.3 :cli:prepareReplayAdapter --console=plain
node experiments/host-observation/run_pi_evaluation.mjs --prepare-replay "$PWD/build/interface-codex-screen-v3.json" --auth=codex --execution=fresh
node experiments/host-observation/run_pi_evaluation.mjs --plan "$PWD/build/interface-codex-screen-v3.json"
node experiments/host-observation/run_pi_evaluation.mjs --execute "$PWD/build/interface-codex-screen-v3.json"
node build/interface-v3-report.mjs
```

- **PASSED:** 94 focused public-shell Kotlin tests, including the production adapter controls.
- **PASSED:** 118 deterministic Node tests. Sixteen optional integrations skip without explicit environment variables.
- **PASSED:** All 36 opt-in integrations, with zero skips or failures.
- **PASSED:** 332 host-experiment Python tests and 23 portable fast-check suites, with zero platform skips.
- **PASSED:** JSON contracts, graph fingerprints, knowledge impact, knowledge citations, Kotlin format, file length, and `:cli:detekt` guards.
- **PASSED:** The full pre-push product gate on the tested source. It completed 647 tasks before inference.
- **PASSED:** Scoped glossary and prose guards. Pinned Vale and Repository Knowledge reports remain under `build/`.
- **ASSESSED:** Prose keeps grade-8 advisories for necessary technical names. Experimental reports sit outside the glossary's declared terminology scope.
- **NOT_MET:** The independently derived 3× assessment preserves all six cases and complete failed spend.
- **NOT RUN:** Live Host qualification, compiler semantic fixtures, workflow confirmation, later interface experiments, deployment, or a broader model sweep.
- **PENDING:** The separately requested development release after merge and green hosted checks.

Earlier failed runs record an expired Pi credential, the old decoder mismatch, and shared Gradle-cache damage.
The task-owned Gradle home resolves the local cache problem without changes to shared caches.
These failures do not count as product failures or unrecorded provider sends.

## Smallest failure reproductions

The original independent `JToon` decoder returns a string instead of an empty array for this production TOON:

```text
items[1]:
  - connections: []
```

The current independent decoder preserves the array.
The corpus also preserves a 40-digit integer without JavaScript numeric conversion.
The [projection review](projection-review.md) records the omitted signature-field control and its repair.

The current screen preserves this exact model failure:

```sh
kast source read --ref exact:v5:AAAAAAAAAAAAAAAAAAAAAg --region callable-body --text complete construe
```

Production admission rejects the command as `INVALID_COMMAND` with exit status 2.
The runner removes no argument and sends no retry.
The [historical reports](README.md) preserve their separate malformed commands and incomplete response-contract instructions.

## Remaining limits

The first scope supplies decision evidence for six fixed synthetic states.
It supplies no general format preference, faster native query claim, billed-cost measurement, or 3× result.
The optional session profile keeps one owned JVM per trial. Its historical timing does not establish current version-3 performance.
The optional concise profile keeps its historical accuracy regression and supplies no accepted improvement.
A later field-selection or context experiment needs a separate scope.
This implementation discards no task evidence and changes no product default.
