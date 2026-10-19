---
type: Engineering Report
title: Kast token POC
description: Paired Pi token totals and independent source-answer assertions for ten Kotlin tasks.
resource: repo://kast/experiments/token-poc
tags: [kast, pi, tokens, experiment]
timestamp: 2026-10-19T12:13:29.139071+00:00
source_revision: 5937ff1f46fbb0914c36226afb52426a4271c29c
code_sources:
  - path: experiments/host-observation/agent_efficiency.py
  - path: experiments/host-observation/agent_efficiency_session.mjs
  - path: experiments/host-observation/agent_efficiency_tokens.py
  - path: experiments/host-observation/agent-efficiency-configs/token-poc-compact.json
  - path: experiments/host-observation/agent-efficiency-configs/token-poc-onboarding.md
---

# Kast token POC

The compact Kast configuration uses **16.0% fewer tokens per correct task** than its paired control.
The efficiency ratio is **1.19×**.
Both arms pass **20/20 task runs** across ten tasks and two repeats.
The median decreases from **52,142** to **36,390.5 tokens**, a **1.43×** ratio.
The 5× target remains aspirational and unmet.

The result covers one repository and ten source questions.
It does not establish broad Kotlin accuracy or population-level gains.
The [later reference experiment](AGENT_EFFICIENCY_REFERENCES.md) isolates two tasks and records provider timing.

## Matched measurements

| Configuration | Correct task runs | Total tokens | Tokens per correct task | Median tokens |
|---|---:|---:|---:|---:|
| Compact control | 20/20 | 1,040,289 | 52,014.45 | 52,142 |
| Compact Kast | 20/20 | 873,478 | 43,673.9 | 36,390.5 |

The compact configuration saves 166,811 tokens across its twenty paired task runs.
Its two repeats save 10.9% and 21.8%, respectively.
All forty compact benchmark sessions finish with complete provider usage and correct final answers.
No benchmark task fails.

The metric sums provider input, output, cache-read, and cache-write tokens across every response.
Input includes instructions, tool definitions, tool responses, and prior context.
Provider output includes reasoning tokens.
The metric counts cached tokens once and includes repeated requests.
Both arms disable automatic retries and compaction.
Failed tasks still consume tokens in the report.
Missing finalized usage produces an unknown total.

## Per-task measurements

Each row gives the median of two runs per arm.
Every final answer passes its independent source oracle in both runs.
A ratio greater than 1 favors Kast.

| Task | Control tokens | Kast tokens | Control/Kast |
|---|---:|---:|---:|
| `discovery.completion` | 11,402.5 | 12,865 | 0.89× |
| `discovery.word` | 35,561.5 | 12,791 | 2.78× |
| `references.text-match` | 99,970 | 87,167.5 | 1.15× |
| `references.retention` | 55,728 | 97,242 | 0.57× |
| `callers.text-match` | 69,164 | 67,322 | 1.03× |
| `callers.limitations` | 52,142 | 46,013 | 1.13× |
| `implementations.coverage` | 30,933.5 | 23,293.5 | 1.33× |
| `implementations.evidence` | 34,057 | 16,734.5 | 2.04× |
| `impact.word-rename` | 80,208 | 36,390.5 | 2.20× |
| `impact.context-rename` | 50,978 | 36,920 | 1.38× |

References consume 18.4% more tokens as an operation family.
The retention-reference task and completion-discovery task favor control on their task medians.
Individual sessions vary substantially.
For example, control uses 7,309 and 63,814 tokens for word discovery.
These results use direct arithmetic without confidence intervals or statistical acceptance gates.

## Configuration and correctness

The source revision is `5937ff1f46fbb0914c36226afb52426a4271c29c`.
Both arms use Pi 1.3.0, `openai-codex/gpt-6.1-sol`, `high` effort, and the same prompts and harness.
Provider caches remain shared and warm.
The run alternates the arm order.
Every task starts a fresh Pi process and session.

The corpus contains two tasks for each requested operation: discovery, references, callers, implementations, and impact analysis.
It covers production declarations, nested implementations, cross-module uses, direct callers, and same-name exclusions.
The coordinator derives expected answers from a complete tracked-source inventory before benchmark inference.
Source excerpts preserve original lines and receiver identities.
The fixed oracles reject missing answers and unsupported extra claims.
Workspace assertions reject source changes.

The final audit repeats the forty compact answer assertions and compares each task prompt across both arms.
It also examines source hashes, shared harness hashes, provider usage, model identity, and workspace deltas.
Every treatment session uses Kast.
Compact treatment makes thirty-eight query calls across twenty sessions.
Intermediate semantic-audit counts remain unknown.
Final-answer assertions do not certify every intermediate semantic choice.

A Pi agent authors the compact brief, and the coordinator reviews its grammar before the seal.
The brief supplies known native query shapes and limits full-schema reads and large outputs.
Kast tool identities, schemas, implementations, model, permissions, budgets, and task prompts remain fixed across the paired comparison.

Use [the compact configuration](agent-efficiency-configs/token-poc-compact.json) with [its pinned brief](agent-efficiency-configs/token-poc-onboarding.md).
The configuration remains an opt-in experiment.
The work includes no merge, release, or deployment.

## Reproduce the run

The private artifact root is `/Users/amichne/.local/state/kast-agent-efficiency/studies/token-poc-5937ff1`.
It contains the sealed corpus, source oracles, environment, configurations, controller snapshots, run manifests, raw records, and final audit.
The corpus identity is `01c14af0fe5a24837c9e340c31be32a6ceb9a8469dfab21bd1a96974e7e9d582`.

Use the artifact root as `POC` in these commands.
Use a new output path for each replay.

```sh
POC=/Users/amichne/.local/state/kast-agent-efficiency/studies/token-poc-5937ff1
uv run --locked --project experiments/host-observation python \
  experiments/host-observation/agent_efficiency.py run \
  --benchmark "$POC/benchmark" \
  --config experiments/host-observation/agent-efficiency-configs/token-poc-compact.json \
  --output /private/tmp/new-kast-token-poc --repeats 2
uv run --locked --project experiments/host-observation python \
  experiments/host-observation/agent_efficiency_tokens.py \
  /private/tmp/new-kast-token-poc --output /private/tmp/new-kast-token-report.json
```

The token report needs every declared pair and repeat.
It preserves per-task failures and unknown usage.
The runner also emits legacy statistical and dollar reports.
Those legacy reports do not decide this token POC.
The POC has no holdout and uses several tasks with related source declarations.

Task totals exclude preparation and coordinator work.

## Validation

- **Passed:** 121 Python cases and 182 `subtests`, strict Pyright, Ruff lint, and Ruff formatting.
- **Passed:** The real report CLI preserves literal paths, expected totals, and expected medians.
- **Passed:** The report CLI rejects an existing output and missing input.
- **Passed:** `verifyJsonContracts`, `knowledgeImpact`, `verifyKnowledgeBase`, and 326 host-observation cases.
- **ASSESSED:** Pinned prose assessment records grade advisories.
- **Failed:** Vale reports spelling and terminology findings in the frozen measured brief.

The stored notes give each unresolved rule, line, and reason.
The measured brief keeps its exact bytes for reproducibility.
Prose scores do not establish reader comprehension.
`validation.json` under the artifact root records the commands and outcomes.
