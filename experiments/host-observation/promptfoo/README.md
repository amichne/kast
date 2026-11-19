# Evaluate Kast with Promptfoo

Use Promptfoo to run agent evaluations. Each comparison asks whether both agents reached the correct answer before it compares tokens or tool calls.
Kast-enabled agents receive an instruction to use Kast for compiler-backed analysis and symbol resolution in Kotlin code and Gradle projects.
Their sessions include the skill and its linked CLI guide. Source-only controls receive neither.

## Start with an offline run

Use the repository Node version from `mise.toml`. Run these commands from the repository root.

```sh
cd experiments/host-observation/promptfoo
npm ci
npm test
npm run eval
```

The default evaluation sends 33 positive and negative decisions through the six interface replay graders. It needs no credentials or Host.
A pass means the grader accepted or rejected the decision as expected.

## Select a suite

Run each configuration from this directory with `npm run eval -- -c FILE -o /absolute/path/results.json`.
Promptfoo uses a fresh model response for each live trial. Provider caches can still serve input tokens.

| Configuration | Cases and arms | Inputs |
|---|---|---|
| `corpus.config.mjs` | Full `ktfmt`, kotlinx.coroutines, and Spring Framework checkouts with three low-reasoning delegates. | [Upstream workspace guide](upstream-workspaces.md) |
| `developer.config.mjs` | Implement features, migrate an API, trace dispatch, and fix a scheduler defect with or without Kast. | Use Pi, Kast, Kotlin/JVM, and macOS. [Developer task guide](developer-tasks.md) |
| `pi.config.mjs` | Six replay graders, paired interface formats, fresh Pi tasks, received results, or a recorded target | Use `KAST_PI_EVAL_PLAN` for a live plan. Use `KAST_PI_EVAL_TARGET` for a recorded target. [Pi guide](../PI_EVALUATION.md) |
| `agent-efficiency.config.mjs` | Paired control and Kast treatment on a curated development or holdout corpus. The optimization plan adds candidate configurations. | Supply the private corpus and `KAST_EFFICIENCY_PLAN`. [Efficiency guide](../AGENT_EFFICIENCY.md) |
| `semantic-campaign.config.mjs` | Source witnesses for baseline, relation, and source variants across the named repositories | Supply `KAST_CAMPAIGN_PLAN` and the specified checkout, executable, and log. [Campaign guide](../CAMPAIGN_RESULTS.md) |
| `skill.config.mjs` | Canonical skill, historical skill revisions, and a matched source lookup with and without Kast | Use Pi and Kast. The repository case uses this checkout or `KAST_EVAL_QCD_WORKSPACE`. |
| `task-access.config.mjs` | Eight contract-17 questions. Both arms get the historical schema. One also gets the historical skill and request preview. | Use Pi, uv, and Git history through `bc18ec841`. Select the model with `KAST_EVAL_TASK_MODEL`. [Original exercise](../../task-access/README.md) |
| `imported-model.config.mjs` | Two identical Git trees with different imported Gradle projects. Each case asks which Kotlin file supplies a function. | Prepare the two workspaces and set `KAST_EVAL_IMPORTED_MODEL_ROOT`. [Fixture guide](imported-model.md) |
| `retained-state.config.mjs` | Two prior queries with identical source. Three arms get different access to the prior query. | Use Pi and Kast. Each trial needs a working Host for the static callback fixture. |

The skill and retained-state suites share `build/promptfoo/static-callback-fixture` in this checkout.
Set `KAST_EVAL_WORKSPACE` to use another imported copy. Set `KAST_EVAL_KAST` to select a specific executable.
Pi uses its signed-in account. Use `KAST_EVAL_PI_AUTH` to select an alternate credential file.
From the repository root, stop the default fixture Host after the last trial.

```sh
kast stop --workspace "$PWD/build/promptfoo/static-callback-fixture"
```

Keep result exports outside participant workspaces. They can include model replies, local paths, and tool receipts.

The six original skill tasks compare the pinned `reference` and `delivered` skill texts. Both receive the current instruction to use Kast.
For the original binary comparison, set `KAST_EVAL_REFERENCE_KAST` and `KAST_EVAL_DELIVERED_DRAFT_KAST` to the recorded executables.
Both live arms use the same installed Kast. The archive records the original binary hashes. [Historical trials](history/pi-skill-cli-results.json)

The task-access suite runs each historical question in a new session. It uses a valid illustrative reference for request drafts.
It asks for scope, signatures, and locations where the original overload question left the intended scope open.
The preview reads the pinned schema and never submits these illustrative references to a Host.
The separate skill suite exercises live discovery, source, and implementations of `QueryCompletionDocument`.

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
A token comparison needs two correct answers on every pair, the same model, and known token counts.
Tokens include input, output, cache reads, and cache writes according to the provider counters. Billed cost depends on provider rates.
Only a token tie permits a comparison of tool calls. The counts include unsuccessful calls.
Repetitions measure consistency on the selected cases. The report makes no population-level estimate.

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
