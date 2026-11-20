# Evaluate Kast with Promptfoo

Use Promptfoo to run agent evaluations. Compare correctness first, then complete dollars, context consumption and model responses, and full task time.
Kast-enabled agents receive the ordinary skill and access to Kast for compiler-backed analysis and symbol resolution in Kotlin code and Gradle projects.
Their sessions include the skill and its linked CLI guide. Source-only controls receive neither.
A correct solution never needs to call Kast.

## Lead with separation

[The separation suite](separation.md) carries the headline claim. Each case has two workspaces with byte-identical tracked text and different compiled answers.
A participant that sees only that text has an expected accuracy of at most one half, whatever its model or tools.
The suite reports `SEPARATED` only when the Kast configuration's 95% lower confidence bound exceeds that ceiling. Every planned trial counts.

The first run on November 20, 2026 reports `NOT_SEPARATED`: Kast 4/12, text 0/12, ceiling 6/12. No Kast answer was wrong.
Each failure traces to a skipped Kast call or to an opaque rejection of a file passed to `--in`. Rerun the unchanged suite after each product or skill change.

The suites below measure other questions. On tasks that the source text decides, their results are mixed and do not support a general uplift claim.

## Start with deterministic checks

Use the repository Node version from `mise.toml`. Run these commands from the repository root.

```sh
cd experiments/host-observation/promptfoo
npm ci --ignore-scripts --no-audit --no-fund
node --test participant-surface.test.mjs review-packet.test.mjs comprehension.test.mjs compare.test.mjs pi-accounting.test.mjs corpus-provider.test.mjs corpus-report.test.mjs portfolio.test.mjs
uv run --locked --project .. pytest -q --noconftest -p no:cacheprovider test_corpus_trial.py
```

These tests exercise the real deterministic rules with pinned inputs or injected process effects. They do not invoke models or the Promptfoo CLI.
The default Pi configuration still contains 33 positive and negative decisions for the six interface replay graders.
The added comprehension mode contains 52 controls, including the archived `ktfmt` answer. A control pass confirms the expected grader decision.

The pinned Promptfoo 0.125.1 CLI attempts telemetry even when `PROMPTFOO_DISABLE_TELEMETRY=1` is set.
Its telemetry-disabled notification still posts to `r.promptfoo.app`; `PROMPTFOO_DISABLE_UPDATE=1` stops the separate update check.
Six CLI integration checks remain blocked in an environment that rejects those requests. `npm test` includes those checks.
The direct checks above do not replace CLI integration qualification. Do not describe the CLI as fully offline until its outbound behavior is resolved.

## Curated portfolio

Keep natural engineering tasks (Panel A) separate from supplied-evidence comprehension diagnostics (Panel B).
Set `KAST_EVAL_PORTFOLIO=curated` for the existing corpus and developer configurations. The default keeps all existing cases.
Set `KAST_PI_COMPREHENSION=controls` for deterministic Panel B controls or `live` for explicit model trials through `pi.config.mjs`.
Those modes cannot be combined with a Pi plan or recorded target. Live mode invokes participant models and needs its normal prerequisites.

| Panel | Case | Distinct question | Current qualification |
|---|---|---|---|
| A | `spring-reactive-server-response-primitive-status-callers` | Can ordinary maintenance identify the exact primitive overload across source files? | Blocked before credentials or models: the corpus baseline lacks ordinary compiler/test inspection. |
| A | `picnic-ansi` | Can a change preserve visible table widths, escape bytes, and multiline behavior? | Existing independent Kotlin effect oracle; extracted upstream fixture and macOS execution prerequisites. |
| A | `coroutine-delay-rounding` | Does Kast help with an ordinary local arithmetic repair? | Existing independent Kotlin effect oracle; extracted conversion function and macOS execution prerequisites. |
| B | `ktfmt-constant-indent-negation-evidence-v1` | Can a model deliver every required exact line from supplied evidence? | Pinned source reconstruction; archived incorrect answer retained separately from synthetic controls. |
| B | `C5` | Does a model distinguish a representative walk from exhaustive scope? | Existing independent replay grader and synthetic contract fixture. |
| B | `source-freshness-after-edit-v1` | Can a model reject retained evidence after the source changes? | Frozen synthetic packet and independent decision oracle. |
| B | `readiness-failure-no-absence-v1` | Can a model distinguish readiness failure from an absent symbol? | Frozen synthetic packet and independent decision oracle. |

Panel B input, oracle, control, and guidance hashes are frozen in [the manifest](comprehension/manifest.json).
Each case also declares its `participantFields`. The participant receives only that projection of the frozen input, and its hash is `participantSha256`.
Curator provenance, owner paths, archived run identifiers, and model names stay in the input file. Private answer files stay with the grader.
Both arms have identical evidence, no tools, a 60-second participant-process limit, and a post-hoc one-response check.
No pre-send response or output-token ceiling is claimed. These diagnostics cannot establish end-to-end engineering uplift.
Native readiness, freshness, and semantic tests keep their existing owners.

The corpus source tools now filter eligible pinned files before applying capture limits and expose recoverable text truncation.
That repair does not qualify their restricted tool set as a competent ordinary engineering baseline.
Curated corpus admission therefore returns `PANEL_A_BASELINE_UNQUALIFIED`; the default catalog remains labeled `RESTRICTED`.

## Blind each participant

These rules adapt the blinding rules in the [`pstack` evaluation playbook](https://github.com/backnotprop/pstack/blob/main/skills/poteto-mode/playbooks/eval.md).
A participant must receive an ordinary engineering request, not a description of the measurement.
[`participant-surface.mjs`](participant-surface.mjs) checks the system text, task text, working directory, workspace paths, and tool names before each launch.
The shared launcher rejects a measurement word with `PARTICIPANT_SURFACE_REJECTED`. The rejection happens before it looks up Pi, copies credentials, or writes a file.
The list names the measurement and its roles, such as `eval`, `judge`, `oracle`, `candidate`, `trial`, `arm`, `panel`, and `promptfoo`.
Engineering words such as `test`, `fixture`, and `diagnostic` remain admissible, because Kotlin tasks ask about tests and Kast reports compiler diagnostics.

Pi renders its working directory in every system prompt. Each participant therefore runs in a new `project-XXXXXX` directory. The name omits the product, so a source-only participant does not learn what the other configuration receives.
Kast replies include workspace roots. The default callback project is therefore `build/projects/callback-service`.
A coordinator only dispatches work. Its record carries `NOT_APPLICABLE`, and each delegate passes the same check as a participant.
Each provider records the decision as `participantSurface` in its metadata.

The check covers delivered text and paths. It does not read the Kast skill, which is the configuration under test, or the content of tool replies.
The repository case still exposes this checkout, including its evaluation files.
The frozen freshness source keeps a fixture comment from its owner file. Participants can read it, and the rule does not block it.
The `absent` skill case now asks about `scheduleCallbackRetry`. Historical trial records keep the earlier name.

## Review answers without labels

Each selected case has a reviewer-only frame in [`portfolio.mjs`](portfolio.mjs). The frame states the configuration under test and three to six concrete criteria. No participant prompt contains a criterion.
The executable oracle remains the authority. A blinded review tests the oracle and the frame.

```sh
npm run review -- build /tmp/kast-retained.json source kast /absolute/empty-directory
# Write reviews.json: {"version":1,"exportSha256":"<from packet.json>","verdicts":{"R01":{"A":"correct","B":"incorrect"}}}
npm run review -- reconcile /absolute/empty-directory/key.json reviews.json
```

`packet.json` pairs rows exactly as `compare` pairs them. A seeded shuffle assigns labels A and B and orders the items.
The packet shows the task, the criteria, and each delivered answer or patch. It omits configurations, models, costs, failure codes, and oracle outcomes.
`key.json` holds the seed, the labels, the oracle outcomes, and bounded tool sequences. Do not open it until every verdict is written.
Judge both answers to an item in one pass on one scale.

Reconciliation reports `AGREE`, `DISAGREE`, `REVIEWER_UNSURE`, `ORACLE_UNASSESSED`, or `UNREVIEWED` for each answer.
Each disagreement includes its configuration and tool sequence, so chain-following is judged from the transcript, not from the answer's account of itself.
The key also records exposures that the packet cannot hide, such as a product name in an answer. Reconciliation reports those items as not blind.
A disagreement changes no recorded outcome. Resolve it in the oracle or the frame, then use a new export.

## Select a suite

Run each configuration from this directory with `npm run eval -- -c FILE -o /absolute/path/results.json`.
Promptfoo uses a fresh model response for each live trial. Provider caches can still serve input tokens.

| Configuration | Cases and arms | Inputs |
|---|---|---|
| `separation.config.mjs` | Two twin cases, a shadowed extension and an imported project, each with three configurations. | Prepare with `npm run separation:prepare`. [Separation guide](separation.md) |
| `corpus.config.mjs` | Full `ktfmt`, kotlinx.coroutines, and Spring Framework checkouts with three low-reasoning delegates. | [Upstream workspace guide](upstream-workspaces.md) |
| `developer.config.mjs` | Implement features, migrate an API, trace dispatch, and fix a scheduler defect with or without Kast. | Use Pi, Kast, Kotlin/JVM, and macOS. [Developer task guide](developer-tasks.md) |
| `pi.config.mjs` | Six replay graders, paired interface formats, fresh Pi tasks, received results, or a recorded target | Use `KAST_PI_EVAL_PLAN` for a live plan. Use `KAST_PI_EVAL_TARGET` for a recorded target. [Pi guide](../PI_EVALUATION.md) |
| `agent-efficiency.config.mjs` | Paired control and Kast treatment on a curated development or holdout corpus. The optimization plan adds candidate configurations. | Supply the private corpus and `KAST_EFFICIENCY_PLAN`. [Efficiency guide](../AGENT_EFFICIENCY.md) |
| `semantic-campaign.config.mjs` | Source witnesses for baseline, relation, and source variants across the named repositories | Supply `KAST_CAMPAIGN_PLAN` and the specified checkout, executable, and log. [Campaign guide](../CAMPAIGN_RESULTS.md) |
| `skill.config.mjs` | Canonical skill, historical skill revisions, and matched source questions with and without Kast | Use Pi and Kast. The repository case uses this checkout or `KAST_EVAL_QCD_WORKSPACE`. |
| `task-access.config.mjs` | Eight contract-17 questions. Both arms get the historical schema. One also gets the historical skill and request preview. | Use Pi, uv, and Git history through `bc18ec841`. Select the model with `KAST_EVAL_TASK_MODEL`. [Original exercise](../../task-access/README.md) |
| `imported-model.config.mjs` | Two identical Git trees with different imported Gradle projects. Each case asks which Kotlin file supplies a function. | Prepare the two workspaces and set `KAST_EVAL_IMPORTED_MODEL_ROOT`. [Fixture guide](imported-model.md) |
| `retained-state.config.mjs` | Two prior queries with identical source. Three arms get different access to the prior query. | Use Pi and Kast. Each trial needs a working Host for the static callback fixture. |

The skill and retained-state suites share `build/projects/callback-service` in this checkout.
Set `KAST_EVAL_WORKSPACE` to use another imported copy. Set `KAST_EVAL_KAST` to select a specific executable.
Pi uses its signed-in account. Use `KAST_EVAL_PI_AUTH` to select an alternate credential file.
From the repository root, stop the default fixture Host after the last trial.

```sh
kast stop --workspace "$PWD/build/projects/callback-service"
```

Keep result exports outside participant workspaces. They can include model replies, local paths, and tool receipts.

The six original skill tasks compare the pinned `reference` and `delivered` skill texts. Both receive the current instruction to use Kast.
For the original binary comparison, set `KAST_EVAL_REFERENCE_KAST` and `KAST_EVAL_DELIVERED_DRAFT_KAST` to the recorded executables.
Both live arms use the same installed Kast. The archive records the original binary hashes. [Historical trials](history/pi-skill-cli-results.json)

The task-access suite runs each historical question in a new session. It uses a valid illustrative reference for request drafts.
It asks for scope, signatures, and locations where the original overload question left the intended scope open.
The preview reads the pinned schema and never submits these illustrative references to a Host.
The separate skill suite exercises live discovery, source, and implementations of `QueryCompletionDocument`.
The `literal-read` case passes only when the agent answers from source without a Kast call.
The `unprepared` case copies the fixture without a Gradle wrapper into an owned temporary directory.
It passes only for the typed preparation cause with no identical retry. It stops that Host and removes the directory.

The interface replay manifest also lists four reserved tasks. Its development suite runs the six cases with published graders.

Host acceptance, SDK admission, corpus preparation, and deterministic adapter tests keep their existing owners.
The historical `run_try_local_pi.mjs` probe uses Pi to invoke a scripted native qualification sequence.
Each agent evaluation starts through a Promptfoo configuration.

## Interpret a comparison

Export both arms in the same invocation. This example repeats each task twice.

```sh
npm run eval -- -c retained-state.config.mjs --repeat 2 -o /tmp/kast-retained.json
npm run compare -- /tmp/kast-retained.json source kast
npm run compare -- /tmp/kast-retained.json kast materialized
```

A wrong answer makes Promptfoo exit with status 100. It still writes the result export.
The report pairs each observed case and repetition. A missing arm, a provider error, or a policy-only assertion blocks a preference.
Each case has equal weight. An arm wins on correctness if it alone solved a pair and the other arm never won a pair.
If each arm wins a different pair, the report shows mixed correctness.
Efficiency comparison requires two correct answers on every pair, matching task and source or evidence hashes, model, effort, permissions, and budgets.
Panel A also requires baseline admission. Both Panel A and Panel B rows need an admitted `participantSurface` under the same vocabulary version. Otherwise the report gives `UNBLINDED_PARTICIPANT_SURFACE` and no preference, even on correctness.
Observed resolved-model mismatches block a preference even on incomplete attempts.
Complete dollars are compared first; a dollar tie permits peak input-context comparison, then model responses, then full task time.
The full-time boundary includes preparation, participant work, independent grading, and cleanup. Participant-only and comprehension-provider timing remain descriptive.
Token and tool-call totals are descriptive counters, not fallback rankings when dollars are missing. No combined quality/cost/speed score is produced.
Repetitions measure consistency on the selected cases. The report makes no population-level estimate.

Pi owns normalization and pricing. The shared receipt owner retains native usage, resolved identity when supplied, copied rate-catalog identity, cost provenance, and bounded delivered evidence.
It distinguishes provider-reported charges from catalog estimates and incomplete accounting. Estimates without applicable rate lineage cannot rank dollars.
Reasoning tokens stay within output totals; one-hour cache writes stay within cache-write totals. Duplicate event representations do not add cost twice.
Unknown failed attempts, omitted records, and unknown helper costs keep known subtotals without presenting a complete total.
Recorded completeness covers delivered Pi events; it is not a billing-invoice reconciliation.

Corpus reports keep failed, missing, and cached rows visible, preserve full delivered answers within the participant output boundary, and keep bounded tool evidence.
The report counts participant/helper costs once and separates coordinator overhead. Grader and coordinator phases have separate timing roles.
A response-cache replay is not assessed as a fresh comparison. Historical exports remain unchanged and acquire no invented dollar values.

The historical comparisons in the next three sections predate the current skill pointer. Their scores describe the original prompts.

## Which Kotlin file supplies the implementation?

The imported-model suite gives the agent two checkouts with identical tracked Kotlin and Gradle files.
Each contains two implementations of `formatInvoiceReference(Long)`. A file outside the checkout selects which project Gradle imports.
The source-only agent receives the same information in both cases. The correct file differs.

| Access | Correct paths | Tokens | Tool calls |
|---|---:|---:|---:|
| Tracked project inputs | 0/2 | 542 | 0 |
| Tracked project inputs and Kast | 2/2 | 1,716 | 2 |
| Tracked project inputs and external Gradle selection | 2/2 | 605 | 0 |

Pi answered `UNKNOWN` twice from the tracked inputs. Both Kast queries returned the independently expected file with exhaustive coverage and zero failures or omissions.
Supplying the external selection also let Pi solve both cases. This identifies which missing fact Kast supplied. [Trial records](history/imported-model.json)

## Which declaration did the prior query retain?

A source file can show that `alphaSink` and `betaSink` both exist. It cannot tell an agent which declaration a previous query retained.
The retained-state suite issues one of two exact-name queries before each trial.
The independent expected answer comes from that selection. Both cases deliver identical source and question text to the source-only agent.
A single answer from that input must be wrong in at least one case.
All arms use the same answer oracle. A separate metric records whether a Kast read supports the answer.
The Kast arm can read the issued result. A third arm receives the previous query directly from the setup record.

| Access in the recorded run | Correct | Tokens | Tool calls |
|---|---:|---:|---:|
| Source only | 0/4 | 1,197 | 0 |
| Source and Kast result access | 4/4 | 5,705 | 4 |
| Source and prior query record | 4/4 | 1,218 | 0 |

Pi used `openai-codex/gpt-6.1-sol` at low effort. Each source-only answer was `UNKNOWN`.
Each Kast answer matched a completed read of its issued result. The agent with the prior query record answered correctly in all four trials without a tool call.
This establishes the need for prior state on this task. Claims about ordinary declaration search, compiler reasoning, and edits need their own matched trials. [Trial records](history/retained-state.json)

## Where the tool helped less

Both agents found the declaration of `invokeCallback` with one ordinary source search.
The agent with Kast available chose the same source tool as its control.

Both historical skill revisions passed six tasks with their recorded executables.
The revised skill used 20,761 tokens and nine calls. The earlier skill used 28,581 tokens and ten calls.

Both agents failed the separate `QueryCompletionDocument` task. One sent a rejected source request.
The other returned the expected conclusion but missed the requested source window and destination scope.
A direct three-call Kast sequence satisfied the oracle. These failures measure how agents used the available operations. [Skill trial records](history/skill-comparison.json)
