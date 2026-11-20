# Separate Kast from text-only answers

This suite measures one claim about questions that tracked source cannot decide.
On those questions, an agent with Kast answers correctly more often than any agent without it can.
The claim needs no baseline model and no assumption about baseline competence.

## Why the ceiling is a proof

Each case has two worlds. The participant-visible text is byte-identical in both, and the expected answers differ.
A text-only participant receives the same request in each world. Its answer distribution therefore cannot depend on the world.
Say the expected answers are `a` and `b`. Its expected number of correct answers per pair is `P(a) + P(b)`, and that sum is at most 1.
Its expected accuracy is at most one half. With `k` worlds and pairwise distinct answers, the ceiling is `1/k`.
That ceiling holds for any model, effort, prompt, or text-only tool set.

The Kast configuration separates when the one-sided 95% Clopper-Pearson lower bound of its observed accuracy exceeds the ceiling.
With three repetitions per world and two cases, 12 correct answers out of 12 give a lower bound of 0.779.
Each planned trial counts. A provider error counts as a wrong answer for the configuration that produced it. A missing or unbalanced plan has no verdict.

Admission enforces both conditions before any model call.
[`separation.mjs`](separation.mjs) renders each delivered prompt, requires identical `source` and `kast` bytes across worlds, and requires distinct expected sets.
The report rechecks the recorded prompt hashes and expected sets from the export, and it regrades every answer.

## Cases

| Case | Input outside the checkout | Question | `west`/`adapter-a` | `east`/`adapter-b` | Expected answer source |
|---|---|---|---|---|---|
| `shadowed-extension` | `../ledger.jar`, a binary dependency outside the checkout | Which functions call the tracked extension `Ledger.post(Int)`? | `NONE` | `refund` | `javap -c` of the application compiled against each jar |
| `imported-project` | `../selection.txt`, read by `settings.gradle.kts` | Which file supplies `formatInvoiceReference(Long)` to the imported main source set? | `adapter-a/…/InvoiceReference.kt` | `adapter-b/…/InvoiceReference.kt` | The selection file that the fixture writes |

In `shadowed-extension`, one jar declares the member `Ledger.post(Int)`. An applicable member takes precedence over an extension, so the extension has no callers there.
The other jar declares only `post(Long)`, so `refund(cents: Int)` calls the extension.
In both worlds, the literal in `settle()` resolves to the member `post(Long)`. Reading the call sites alone suggests two callers, and both worlds have fewer.
The question is the ordinary safe-delete question. The trap reaches real repositories through dynamic versions, snapshots, and private artifact repositories.

## Configurations

| Configuration | Receives |
|---|---|
| `source` | The question and a snapshot of every tracked text file. |
| `kast` | The same prompt bytes, the canonical Kast skill, and the read-only `kast` CLI tool against its world's workspace. |
| `materialized` | The same text plus the one untracked fact: the selection file, or `javap -p` of the resolved jar. |

All three use the same model, effort, answer contract, and blinding check. The `kast` participant must choose its own query.
The `materialized` configuration is a positive control. It shows whether the missing fact alone suffices, or whether the answer also needs compiler resolution.

## Run the suite

Run these commands from this directory with the repository's Node version, `kotlinc`, `javap`, Pi, and an installed Kast.
Choose a root under an existing parent. Keep words such as `eval`, `trial`, and `experiment` out of the path, because participants see workspace paths in Kast replies.

```sh
root="$(mktemp -d /private/tmp/kast-twins.XXXXXX)/root"
npm run separation:prepare -- "$root"
KAST_WORKSPACE_DEPENDENCY_SHARING=SHARED npm run separation:qualify -- "$root"
KAST_WORKSPACE_DEPENDENCY_SHARING=SHARED KAST_SEPARATION_ROOT="$root" npm run eval:separation -- -o "$root/../results.json"
npm run report:separation -- "$root/../results.json"
```

Qualification compiles the application against each jar, reads the bytecode, and requires a complete, exhaustive Kast reply that matches it in each world.
Set `KAST_SEPARATION_CASES` to a comma-separated list to select cases. Stop the four Hosts after the run with `kast stop --workspace PATH`.

## Results on November 20, 2026

Pi 1.5.2 ran `openai-codex/gpt-6.1-sol` at low effort with Kast 0.0.4551. Each configuration ran each world three times. [Trial records](history/separation.json)

| Configuration | `imported-project` | `shadowed-extension` | Pooled | Lower bound | Wrong answers |
|---|---:|---:|---:|---:|---:|
| Text-only ceiling, any participant | 3/6 | 3/6 | 6/12 | — | — |
| `source` | 0/6 | 0/6 | 0/12 | 0.000 | 0 |
| `kast` | 4/6 | 0/6 | 4/12 | 0.123 | 0 |
| `materialized` | 6/6 | 2/6 | 8/12 | 0.391 | 2 |

The verdict is `NOT_SEPARATED`. Every `source` answer was `UNKNOWN`. No `kast` answer was wrong.

Every failed `kast` answer traces to one of two causes, and neither is Kast resolving wrongly:

- **The participant did not run Kast.** On `imported-project`, the four trials with a Kast call were all correct. The two abstentions made no tool call.
- **Kast rejected a file as a directory scope.** On `shadowed-extension`, five trials passed the extension's file to `--in`.
  Kast returned `COMPLETION_UNPROVEN` with `DISCOVERY_INCOMPLETE`, and its explanation says that the rejection does not establish absence.
  The same query with the directory `src/main/kotlin/billing` completes in both worlds, as qualification shows. The participant correctly refused to claim a result.
  The sixth trial used `--relation-source-set`. The suite's tool sandbox predated that flag and rejected the call. The sandbox now admits it.

The `materialized` result matters on its own. Given the jar's `javap -p` listing, the participant answered `settle` and `refund` in two of three `east` trials.
The literal in `settle()` resolves to the member `post(Long)`, so the answer needs compiler resolution, not only the missing fact.

## Move the verdict

The verdict changes only through product or skill changes, measured by rerunning this unchanged suite.
The two recorded causes identify the next changes. `--in PATH` needs a typed argument rejection or file support.
The skill needs routing for questions that the tracked build cannot decide.
A rerun after each change gives a before-and-after comparison on identical prompts.
Do not change the prompts, cases, or answer contract to move the verdict. Add a new case version instead, and keep this record.

## Limits of the claim

The ceiling bounds participants whose only world-specific input is tracked text.
An agent that can run Gradle, `javap`, or a compiler can observe the untracked input itself. For that agent, the comparison becomes cost and reliability, which this suite does not measure.
The `kast` configuration also receives the skill and a tool. The `materialized` control shows which part of the gain the missing fact explains.
Two fixtures are small by design. They establish that the separation exists and that Kast closes it. They do not estimate how often such questions occur in a codebase.
