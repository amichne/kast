# Evaluate Kotlin developer tasks

Use this suite to measure whether an agent can change working Kotlin code or trace a call through a service.
Each Promptfoo case gives both agents the same requirement, source tree, editor, and compiler.
One agent also gets read access to Kast. Both can solve each task with the supplied source.

| Case | Developer request | Evidence for a correct result |
|---|---|---|
| `locker-pickup` | Extend parcel checkout with a locker delivery option. | A client exercises the endpoint and pricing rules, including quantity, weight, zone, and existing delivery modes. |
| `audit-v2` | Replace three string arguments with a typed event across the parcel audit API. | A client calls booking, cancellation, and replay. It examines emitted events, journal entries, the public method signature, and the separate analytics API. |
| `dispatch-trace` | Explain the first and second dispatch of the same remote parcel. | The answer must identify the selected overload, concrete carrier, ordered calls, and both outcomes. A Kotlin witness exercises the two paths. |
| `coroutine-delay-rounding` | Fix a scheduler that resumes too early after it converts a duration to milliseconds. | A client exercises fractions, exact milliseconds, large finite durations, and both infinities. |
| `picnic-ansi` | Extend Picnic so terminal colors preserve table alignment. | A client compares the rendered text, including escape sequences, visible columns, and multiline cells. |

## Run a comparison

Use macOS with `kotlinc`, Java, Pi, and an installed Kast Host. The hidden Kotlin tests run in the macOS sandbox.
Use the signed-in Pi account or set `KAST_EVAL_CODEX_AUTH` to the path of a Codex credential file.
Run these commands from `experiments/host-observation/promptfoo` after `npm ci`.

```sh
npm run eval -- -c developer.config.mjs -o /tmp/kast-developer.json
npm run compare -- /tmp/kast-developer.json source kast
```

For one task, add `--filter-pattern audit-v2` to the evaluation command. Add `--repeat 3` to measure three fresh trials per arm.
Select the participant with `KAST_EVAL_PI_MODEL` and `KAST_EVAL_PI_EFFORT`. Both arms use the same settings.
Keep exports outside the participant workspace.

The baseline compiles before each trial. The Kast arm also needs a complete query against the participant project.
Participants can compile their edits during the trial. The grader then compiles candidate sources with a separate Kotlin client.
Changes outside the task's edit scope fail the case. The trace task accepts an exact JSON answer and permits no source edits.
The result records the proposed tool calls, actual tool calls, Kast calls, and provider token counts.
Each edit result includes a Git patch for the permitted Kotlin files. A reviewer can apply this patch to a fresh fixture.
The comparison needs matching hashes for the initial sources and task text.
If a participant reaches its time or output limit, the grader examines the available work. An unknown token total blocks a cost comparison.
A failed setup yields a provider error. A patch that fails the independent client yields an incorrect result.

## Source of each task

The parcel tasks share a purpose-built service. Its two audit interfaces have the same short name.
The dispatch endpoint selects an overload and uses the concrete carrier for the destination.

Firebender lists the scheduler defect as `Kotlin__kotlinx.coroutines-3921`.
The fixture extracts the pre-fix conversion from revision `2b5d93f2` and uses the Kotlin standard library.
It reproduces the rounding defect without a clock. It covers the conversion only.
The complete upstream coroutine scheduler and Firebender harness are outside this case. [Dataset at the pinned revision](https://github.com/firebenders/Kotlin-bench/blob/90867afd46b87a2e848721e5d9d97943e207ddc0/agent-bench/data/kotlin_bench.json) · [Upstream fix and tests](https://github.com/Kotlin/kotlinx.coroutines/pull/3921)

The Picnic task starts with all seven Kotlin production files at revision `cd569782`.
The fixture uses the repository Gradle wrapper and removes obsolete `@JvmDefault` annotations for the current Kotlin compiler.
The issue and merged fix are public, so a model can know the change from prior training. [Picnic issue #20](https://github.com/JakeWharton/picnic/issues/20) · [Upstream fix and tests](https://github.com/JakeWharton/picnic/pull/22)

## Recorded developer trials

Pi used `openai-codex/gpt-6.1-sol` at low effort. Each arm ran each task once.
Both arms passed all five tasks. The agent with Kast available used the ordinary project tools for every task.

| Access | Correct | Tokens | Tool calls | Participant Kast calls |
|---|---:|---:|---:|---:|
| Source and compiler | 5/5 | 95,691 | 45 | 0 |
| Source, compiler, and Kast | 5/5 | 105,951 | 46 | 0 |

Each Kast preflight completed before the participant started. The tool-call counts cover the participant session.
The source arm used fewer tokens in this run. One trial per arm cannot establish a consistent cost difference.
The record includes the task and source hashes, all eight edit patches, both trace answers, and the token counts. [Trial record](history/developer-tasks.json)

To compare the saved results, run this command from the suite directory.

```sh
npm run compare -- history/developer-tasks.json source kast
```
