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
Six correct answers out of six give a lower bound of 0.607. Fifteen out of 18 give 0.623.
Each planned trial counts. A provider error counts as a wrong answer for the configuration that produced it.
A missing or unbalanced plan has no verdict.

Admission enforces both conditions before any model call.
[`separation.mjs`](separation.mjs) renders each delivered prompt, requires identical `source` and `kast` bytes across worlds, and requires distinct expected sets.
The report rechecks the recorded prompt hashes and expected sets from the export, and it regrades every answer.

## Cases

| Case | Input outside the checkout | Question | First world | Second world | Expected answer source |
|---|---|---|---|---|---|
| `shadowed-extension` | `../ledger.jar` | Which functions call the tracked extension `Ledger.post(Int)`? | `west`: `NONE` | `east`: `refund` | `javap -c` of the application compiled against each jar |
| `subtype-extension` (held out) | `../payments.jar` | Which functions call the tracked extension `Payment.describe()`? | `north`: `checkout`, `refund` | `south`: `refund` | `javap -c` of the application compiled against each jar |
| `imported-project` | `../selection.txt`, read by `settings.gradle.kts` | Which file supplies `formatInvoiceReference(Long)` to the imported main source set? | `adapter-a/…/InvoiceReference.kt` | `adapter-b/…/InvoiceReference.kt` | The selection file that the fixture writes |

In `shadowed-extension`, one jar declares the member `Ledger.post(Int)`. An applicable member takes precedence over an extension, so the extension has no callers there.
The other jar declares only `post(Long)`, so `refund(cents: Int)` calls the extension.
In both worlds, the literal in `settle()` resolves to the member `post(Long)`. Reading the call sites alone suggests two callers, and both worlds have fewer.
The question is the ordinary safe-delete question. The trap reaches real repositories through dynamic versions, snapshots, and private artifact repositories.

In `subtype-extension`, the tracked code declares `Payment.describe()` and `Any.describe()`. In `north`, the jar's `Card` implements `Payment`, so `checkout(card)` selects the more specific extension.
In `south`, `Card` does not implement `Payment`, so the same call selects `Any.describe()`. `refund(payment)` uses `Payment.describe()` in both worlds.
This case was committed in `af9a6a6325` before any trial ran on it. Its qualification selects the declaration by position, so the unrevised product qualifies it.

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
Set `KAST_SEPARATION_CASES` to a comma-separated list to select cases. Set `KAST_EVAL_KAST` to select a Kast executable.
Stop the six Hosts after the run with `kast stop --workspace PATH`.

To compare runs, pass earlier exports to the report. It applies a one-sided Fisher exact test to the Kast configuration on each shared case and pooled.

```sh
npm run report:separation -- AFTER.json --before BEFORE.json --before HOLDOUT-BEFORE.json
```

## First run: November 20, 2026

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

## Rerun after the product fix

Commit `ae60bfe882` changed two things. The product now reads a file named by a descendants `--in` scope; a direct scope still rejects it.
The skill now routes questions that depend on input outside the tracked files to Kast. The prompts did not change: both original cases reproduced their recorded prompt hashes.
The rerun used a local product built from that commit, version `0.0.466166`, without release qualification.
The held-out case first ran against the installed Kast 0.0.4551 and the earlier skill. [Trial records](history/separation-progress.json)

| `kast` configuration | `imported-project` | `shadowed-extension` | `subtype-extension` (held out) | Pooled |
|---|---:|---:|---:|---:|
| Before | 4/6 | 0/6 | 0/6 | 4/18 |
| After | 3/6 | 6/6 | 6/6 | 15/18 |
| One-sided Fisher p | 0.879 | 0.0011 | 0.0011 | 0.0003 |

After the change, the pooled verdict is `SEPARATED`: lower bound 0.623 against a ceiling of 0.5.
Both binary-dependency cases separate on their own. `imported-project` does not. `source` stayed at 0/18, and no `kast` answer was wrong in any run.

The gain comes from the product fix:

- Every correct after-run answer on the two binary-dependency cases followed a complete Kast reply to `--in` with a file path. The earlier product rejected that exact form in all 11 such trials.
- The held-out case failed before the change for the same reason. It was not used to design the fix, but it exercises the same defect. It confirms the fix on a new resolution rule, not on a new failure.
- The skill change had no measurable effect. In every `imported-project` miss, the participant answered `UNKNOWN` without opening the skill or calling a tool.
  Its decision whether to consult Kast came from the one-sentence instruction that this suite supplies, which the skill body cannot change.
  The installed product supplies the skill's `description` instead. Deliver that text in the suite before you measure a routing change.

The `materialized` control reached 15/18 with one wrong answer: `settle` and `refund` on `east`, which repeats the literal-resolution error.
Kast reached the same count with no wrong answers.

## Move the verdict further

Change the product or the skill, then rerun this unchanged suite and compare the exports with the report's `--before` option.
Do not change the prompts, cases, or answer contract to move a verdict. Add a new case version instead, and keep each record.

## Limits of the claim

The ceiling bounds participants whose only world-specific input is tracked text.
An agent that can run Gradle, `javap`, or a compiler can observe the untracked input itself. For that agent, the comparison becomes cost and reliability, which this suite does not measure.
The `kast` configuration also receives the skill and a tool. The `materialized` control shows which part of the gain the missing fact explains.
The fixtures are small by design. They establish that the separation exists and that Kast closes it on these cases. They do not estimate how often such questions occur in a codebase.
The rerun's product is a local build without release qualification.
