# Interface decision replay

This experiment compares one next decision after a fixed, synthetic Kast result.
It extends `run_pi_evaluation.mjs` and preserves fresh and received-result modes.
It composes no Host, MCP connection, or native semantic call.

The first screen declares six development cases, two formats, two repetitions, eight workers, and at most 24 provider sends.
The twelve pair IDs and balanced arm order appear in the no-inference plan.
Repetitions reuse six tasks and do not supply twelve independent tasks.

## Prepare and run

Prepare the production adapter with the repository toolchain:

```sh
mise exec -- ./gradlew :cli:prepareReplayAdapter
node experiments/host-observation/run_pi_evaluation.mjs --prepare-replay /absolute/initial-plan.json
node experiments/host-observation/run_pi_evaluation.mjs --plan /absolute/initial-plan.json
node experiments/host-observation/run_pi_evaluation.mjs --execute /absolute/initial-plan.json
```

For one case, append `C6` to `--prepare-replay`.
For the current Codex login, append `--auth=codex`. The default remains the Pi credential source.
This plan declares four trials and at most four provider sends.
Each execution needs a new output directory.
The planner reads artifact identities without credentials, SDK sessions, or network calls.

The execution first projects every complete arm through the installed SDK with synthetic credentials and no network.
An encoding or input failure invalidates both arms before either provider send.
The live worker compares the admitted projection with the complete serialized provider payload.
It permits one actual send, one shell call, and one Kast driver entry within 60 seconds.
Further SDK requests stop before transport.
Retries, compaction, and cache warming remain off.
The requested output cap is 2,000 tokens. Backend enforcement remains unproved without provider evidence.

## Cases and authority

C1 selects a specific overload. C2 selects a complete callable body.
C3 preserves the directory and source set. C4 reads an issued retained page.
C5 preserves insufficient coverage. C6 repairs the exact `INVALID_FIELDS` error.
C5 permits a finish decision. The other cases permit an invocation.
No case needs clarification because each original task supplies the necessary facts.

The visible prefix contains the original task, a preceding Bash call, and its actual SDK tool-result representation.
The same shell declaration and contract apply to both formats.
The Kotlin renderer receives raw canonical JSON bytes, without a JavaScript numeric conversion.
Both arms keep the same information projection, references, qualifications, stderr, and exit status.
The held-out manifest declares four more tasks before inference. The initial screen does not select them.

`pi_evaluation_replay_grading.mjs` owns independent outcome annotations.
Source annotations bind fixture identities to exact references, signatures, and source ranges.
The shell can read only fixture files and runtime files. It cannot read evaluator files or credentials.
Seatbelt blocks network access and production executables.
The broker counts entries before request decoding and production argument parsing.
It passes actual `argv` and `stdin` to the test-only Kotlin adapter.
The production parser, lowering, input decoder, and public admission own command validity.
Admitted commands still need independent target, scope, reference, and coverage grades.
A supported transition returns its declared synthetic fixture. Unsupported transitions return an explicit fixture failure.

Private run directories contain exact inputs, commands, `argv`, `stdin`, `stdout`, `stderr`, provider payloads, and immutable trial receipts.
Only the controller writes the aggregate report.
The report includes every planned trial, failed send, invalid arm, incomplete decision, and unavailable measurement.
Incomplete model outcomes remain unsuccessful in the correctness denominator.
Missing usage remains unavailable. Cache tokens count once. Reasoning remains within output tokens.

## Current limits

The initial run uses `openai-codex/gpt-6.1-sol` with high effort through Pi 1.4.0.
The original offline report used an expired Pi credential and has status `NOT_RUN`.
The first Codex-backed screen completed 24 measured trials.
[The live report](first-live.md) keeps its original results and response-contract findings.
The current hill climb uses the [performance target](hill-climb.md).
The report selects no format winner.

`JToon` 3.0.0 decodes a nested empty array as a string in a list-item object.
Its emitted empty-array syntax agrees with the [TOON specification](https://github.com/toon-format/spec/blob/main/SPEC.md#91-inline-primitive-arrays).
The current test adapter uses the published `Ktoon` 6.0.0 decoder as an independent oracle.
All six development inputs and the complete fixture corpus pass equality through that decoder.
The old failure does not establish model-visible data loss.
The initial report preserves its recorded decoder failure and invalid pairs.
It changes no product default, schema, semantic algorithm, or reference authority.

The smallest reproduction uses this canonical input:

```json
{"items":[{"connections":[]}]}
```

The production renderer emits this TOON:

```text
items[1]:
  - connections: []
```

`JToon` decodes `connections` as the string `"[]"`.
The old guarded adapter reported `REPLAY_ENCODING_MISMATCH`.
The old decoder also returned an integer beyond the `Long` range as a string.
Current Kotlin regression cases preserve exact arrays, strings, and integers with the independent decoder.
The experiment does not repair decoded data or shorten an arm.

## Focused evidence

```sh
node --test experiments/host-observation/pi_evaluation_replay*.test.mjs
KAST_REPLAY_ADAPTER_MANIFEST="$PWD/cli/build/replay-adapter/manifest.json" node --test experiments/host-observation/pi_evaluation_replay_adapter.test.mjs
KAST_REPLAY_TEST_PLAN="$PWD/build/interface-replay-plan.json" node --test experiments/host-observation/pi_evaluation_replay_sdk.test.mjs
mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*' :distribution:output:test verifyJsonContracts
```

The SDK test uses synthetic transport and no provider credentials.
The adapter tests use the real restricted shell and JVM admission, without a semantic executor.
These tests supply no live-model, compiler-fixture, installed-product, or native-host evidence.
The [initial report](initial-comparison.md) records the acceptance status and artifact identities.
