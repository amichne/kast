# Corrected initial format screen

The corrected version-3 screen completed 24 actual sends with complete usage and no excluded trials.
TOON scored 11 of 12. JSON scored 12 of 12.
The paired result has one JSON win and eleven ties across twelve pairs.
These repetitions cover six tasks. They do not establish a general format winner.
The 3× token and latency target remains `NOT_MET`.

| Measure | TOON | JSON |
| --- | --- | --- |
| Correct decisions | 11 of 12 | 12 of 12 |
| Total provider tokens | 11,777 | 11,229 |
| Tokens per correct decision | 1,070.64 | 935.75 |
| Median trial duration | 12.22 seconds | 11.39 seconds |
| Actual provider sends | 12 | 12 |
| Native semantic calls | 0 | 0 |

The [target assessment](screen-v3-target.json) records a 1.144× token ratio and a 1.140× paired latency ratio for JSON against TOON.
The latency measure uses the median of six per-case median paired ratios.
All six cases support correct JSON decisions without a per-case accuracy loss in this screen.
Neither ratio reaches 3×. Billed cost remains unavailable.

## Replay state and source

The [projection review](projection-review.md) records the corrected preceding commands before inference.
C1–C5 explicitly request the three captured signature fields.
Every original task, canonical value, reference, row, qualification, and continuation stays unchanged.
The version-3 state digest now binds the preceding call, stderr, and exit status.
Historical version-1 and version-2 screens remain development evidence, with their original verdicts and costs.
They do not supply current acceptance or a current performance baseline.

The clean implementation source is `eaa4efbb528be4652409f2de32915e67ed8826a0`.
The working-tree digest is `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945`.
The [manifest](screen-v3-manifest.json) pins the plan, artifacts, SDK files, model settings, cases, and immutable receipt hashes.
The adapter aggregate digest is `4b5083255f505afc2682a6c69732c35c130249f07429cd2def97370eeab3fd4d`.
The model remains `openai-codex/gpt-6.1-sol`, with high effort through Pi 1.4.0.
The runner uses the existing Codex ChatGPT credential in memory through the same Pi provider route.
No separate Codex agent loop, provider substitution, or credential-file change occurs.

## Admission and accounting

All 24 complete inputs survived the installed SDK and local limits before inference.
All twelve pairs passed input admission before either arm sent.
Each actual provider payload contained the intended result exactly once.
Each trial kept one provider send and at most one Bash call and Kast driver entry.
All 24 native-call counts equal zero.
Retries, compaction, and cache warming stayed off. No calibration request occurred.

The [comparison](screen-v3-comparison.json) contains every planned trial, paired measure, usage record, parser rejection, and unavailable measurement.
Both formats keep twelve eligible decisions. No incomplete outcome, invalid trial, missing usage, or missing pair occurred.
The incorrect TOON decision remains unsuccessful in its denominator.
Excluded invalid trials: none.
The runner preserved raw commands, actual `argv`, `stdin`, `stdout`, `stderr`, and exit statuses.
Private records remain under `build/interface-codex-screen-v3.json.run/`.

The [campaign ledger](campaign-ledger.json) counts all historical work and this screen.
It totals 120 actual sends and 113,602 provider tokens.
Its 111 correct labels among 120 eligible historical decisions do not estimate current version-3 accuracy.
The current acceptance cohort contains only these 24 version-3 trials.

## Smallest model failure

`C2-r1-TOON` appended the literal argument `construe`:

```sh
kast source read --ref exact:v5:AAAAAAAAAAAAAAAAAAAAAg --region callable-body --text complete construe
```

Production admission returned `INVALID_COMMAND`, exit status 2, and the existing supported-command guidance on stderr.
The runner did not repair or retry the command.
The JSON pair member and the second TOON repetition completed the task correctly.
This single failure does not establish a general encoding defect.

## Exact commands

The required full pre-push gate passed before inference.
The adapter preparation used the same task-owned Gradle home as that gate.

```sh
GRADLE_USER_HOME="$PWD/build/interface-gradle-home" mise exec -- ./gradlew -Pversion=0.50.3 :cli:prepareReplayAdapter --console=plain
node experiments/host-observation/run_pi_evaluation.mjs --prepare-replay "$PWD/build/interface-codex-screen-v3.json" --auth=codex --execution=fresh
node experiments/host-observation/run_pi_evaluation.mjs --plan "$PWD/build/interface-codex-screen-v3.json"
node experiments/host-observation/run_pi_evaluation.mjs --execute "$PWD/build/interface-codex-screen-v3.json"
node build/interface-v3-report.mjs
```

The final command copies measured reports and derives manifest hashes, target arithmetic, and campaign totals without inference.
It does not change original trial receipts.
No live Host run, workflow confirmation, later interface experiment, product default change, deployment, or release forms part of this screen.
