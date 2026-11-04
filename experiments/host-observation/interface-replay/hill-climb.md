# Interface performance target

The goal stays active. The first Codex-backed screen supplies measured evidence, but no 3× improvement exists yet.
The [v2 baseline](baseline-v2.md) records all twelve correct TOON decisions under explicit limitation-code instructions.
The initial comparison and its failures remain historical evidence at their recorded source revisions.

## Acceptance contract

The user-selected target uses one-third the total provider tokens per correct decision and one-third the paired median latency.
The candidate must preserve accuracy for each declared case. Each case needs a successful candidate decision.
All failed model work counts toward total tokens. Invalid trials block a performance claim.
Missing usage, timing, identities, or provider evidence produce `UNMEASURED`.
Billed cost remains unavailable. Provider tokens supply a work measure, not an invoice amount.

Each cohort declares its case IDs and two repetitions before inference.
The two repetitions do not create independent tasks.
The comparison preserves the task, canonical evidence, grader, model, effort, SDK files, and effect limits.
An explicit `EXECUTION` variation keeps the same format and visible instructions.
An explicit `INSTRUCTIONS` variation permits only the declared `full` to `concise` contract change.
Both variations preserve the production public schema. An undeclared change rejects the comparison.
It rejects a changed workload, changed settings, or a contradictory grade.
Only `LIVE_MODEL` receipts with an exact provider projection can support `TARGET_MET`.
Synthetic transport supplies deterministic tests of the arithmetic.

Latency covers worker startup through owned-child cleanup and report delivery.
Queue time remains separate. The report also records worker, setup, provider, and shell durations.
Each case supplies the median of its paired latency ratios.
The overall speed ratio uses the median of those case ratios.
The report keeps every individual ratio and case result.
It supplies no tail-percentile or native-query speed claim.

## Current changes

The Kotlin test adapter uses the published `Ktoon` 6.0.0 decoder for independent equivalence proof.
The production renderer still uses `JToon` 3.0.0. The output bytes and defaults stay unchanged.
All 15 input and transition documents pass exact-value and production-byte assertions.
The corpus includes nested empty arrays, the literal string `"[]"`, `Long.MAX_VALUE`, and a 40-digit integer.
The independent decoder resolves the previous A3 proof failure without data repair.

The [pinned decoder source](https://github.com/lukelast/ktoon/blob/2937d35430e511fff911e2c5ce0aa2604211243e/ktoon/src/commonMain/kotlin/com/lukelast/ktoon/serializers/KtoonJsonElementSerializer.kt) preserves numeric lexemes.
The version catalog and Gradle metadata pin the five published dependency artifacts.
This change supplies no measured model-performance improvement.

## Compare receipts

Use the existing runner with a JSON input file:

```sh
node experiments/host-observation/run_pi_evaluation.mjs --assess-target /absolute/target.json
```

The input contains `target`, `baseline`, and `candidate`.
The two cohorts contain original per-trial receipts from separate declared arms or runs.
The target has this shape:

```json
{
  "factor": 3,
  "dimensions": "TOKENS_AND_LATENCY",
  "caseIds": ["C1", "C2", "C3", "C4", "C5", "C6"],
  "repetitions": 2
}
```

`TOKENS` and `LATENCY` select one dimension when the user explicitly selects that acceptance criterion.
A result of `TARGET_MET` exits with status 0. `NOT_MET` exits with status 1.
`UNMEASURED` and `INVALID_COMPARISON` exit with status 2.
The command loads no credentials, SDK sessions, or native services.

## Next measured iteration

The next baseline must use the current pinned model and existing 24-request screen.
The v1 screen remains intact as [measured diagnostic evidence](first-live.md).
Both complete inputs must pass local limits and encoding equivalence before either arm sends.
The screen keeps six cases, two formats, two repetitions, eight workers, and a 60-second deadline per trial.
Later candidates change one declared factor at a time.
The next factor depends on measured failures and resource use. A smaller response alone does not establish a token or latency improvement.
The held-out cases remain outside development decisions and supply later confirmation.

The runner can read the current Codex ChatGPT login with `--auth=codex`.
The credential stays in memory during execution. Neither credential file changes.
This uses the existing SDK runner and provider route. It does not launch another Codex agent loop.
The experiment does not substitute another provider or weaken a grader to manufacture a gain.

## Isolated adapter session

The `session` execution profile starts one fresh JVM for each trial.
Its typed JSON-line protocol permits one admission request and one render request.
It keeps production parsing, lowering, input decoding, admission, and output bytes.
The Node owner caps frames and stderr, obeys cancellation, and reaps its owned child.
The Kotlin adapter rejects malformed UTF-8, excess requests, and oversized responses with finite causes.
A model-triggered admission response limit remains an unsuccessful incomplete trial.
An unavailable adapter supplies no performance proof.

The worker reuses the exact presentation from its complete SDK preflight.
The proof binds its canonical digest, adapter digest, format, presentation digest, and encoding equality.
The live SDK projection must still match the preflight projection before transport.
Independent SDK sessions keep distinct cache keys. The model-visible input and tool declaration remain identical across execution profiles.
This reuse removes redundant rendering before the timed request. It does not shorten an input or share mutable semantic state.

The `concise` instruction profile changes one global contract for all six cases.
It keeps every task, preceding call, canonical value, command family, and finish schema.
Its smaller byte count supplies no measured token or latency gain.
