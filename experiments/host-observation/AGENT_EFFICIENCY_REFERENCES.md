---
type: Engineering Report
title: Kast reference chains and provider timing
description: Two reference configurations with token gains, source-answer results, and separate provider timing evidence.
resource: repo://kast/experiments/reference-chains
tags: [kast, pi, tokens, references, experiment]
timestamp: 2026-10-19T14:00:00Z
source_revision: 09a572d47fbca9dd9922cc777693190717db0a14
code_sources:
  - path: experiments/host-observation/agent_efficiency.py
  - path: experiments/host-observation/agent_efficiency_session.mjs
  - path: experiments/host-observation/agent_efficiency_audit.mjs
  - path: experiments/host-observation/agent_efficiency_tokens.py
  - path: experiments/host-observation/agent_efficiency_timing.py
  - path: experiments/host-observation/efficiency-tests/test_timing.py
  - path: experiments/host-observation/agent-efficiency-configs/reference-chain-scoped.json
  - path: experiments/host-observation/agent-efficiency-configs/reference-chain-private.json
---

# Reference chains and provider timing

The scoped reference candidate uses **70.7% fewer tokens** than its paired control, with **4/4 correct final answers** in both arms.
Its efficiency ratio is **3.41×**.
The result covers two source questions with two repeats, without a holdout.
The 5× target remains unmet.

Provider timing now records each response separately.
The report separates provider wait from other worker time and keeps output rates with their clock source.
This separation prevents a raw latency comparison from serving as evidence of reduced workflow work.

Correct final answers do not establish private intermediate output.
The scoped candidate still exposes its issued seed reference in one output per session.
The later private-store guide reduces that exposure to three sessions out of four.
Neither guide establishes that agents never see intermediate output.
The [ten-task compact configuration](AGENT_EFFICIENCY_TOKEN_POC.md) remains the broader measured configuration.
The reference candidates remain opt-in experiments.

## Independent answers and controls

The two tasks ask for production files with external code uses of `SymbolTextMatch` and `QueryCompletionRetentionFailure`.
Their fixed source oracles expect five and seven files, respectively.
Imports, declarations, tests, and different same-name symbols do not satisfy those answers.
An unsupported extra file or a missing file rejects the final answer.
The runner also asserts an unchanged workspace.

Both arms use Pi 1.3.0, `openai-codex/gpt-6.1-sol`, `high` effort, and the same ordinary development tools.
Each task starts a fresh process and session.
Both arms disable retries and compaction, share warm provider caches, and alternate their order.
Each candidate comparison changes the treatment brief.
It preserves the model, task prompts, tool contracts, permissions, and resource limits.

The limits are 20 provider requests, 40 tools, 150,000 reported tokens, 6,000 requested output tokens, and 180 seconds.
The token limit controls admission after finalized usage, so one response can exceed the remaining allowance.
Provider enforcement of the output limit remains unproved.

The semantic source remains `5937ff1f46fbb0914c36226afb52426a4271c29c`.
The subject checkout is `813691d02f25efec4c460f7496a4500b2045960e`, which adds experiment files to that source.
Each manifest preserves separate producer and subject identities, corpus identity, configuration digest, controller snapshots, and source assertions.
The corpus identity is `01c14af0fe5a24837c9e340c31be32a6ceb9a8469dfab21bd1a96974e7e9d582`.

## Measured configurations

Each row contains four runs per arm.
Token totals include failed tasks.
An incomplete usage total stays unknown, with a separate known lower bound.

| Treatment brief | Control correct | Treatment correct | Control tokens | Treatment tokens | Decision |
|---|---:|---:|---:|---:|---|
| Scoped chain guide | 4/4 | 4/4 | 270,898 | 79,371 | 70.7% fewer tokens |
| Private-store guide | 4/4 | 4/4 | 256,929 | 100,931 | 60.7% fewer tokens |

The scoped guide adds early production-source filters and one read per file.
It also states the native shapes for `read` and `bash` results.
These changes form one instruction bundle.
The experiment does not isolate their individual effects.
Kast implementations, schemas, and canonical operation identities do not change.

The private-store guide adds native Pi `store()` and `load()` instructions.
It uses 60.7% fewer tokens than its paired control, a 2.55× ratio.
It consumes 27.2% more tokens than the scoped guide across separate sessions.
The scoped guide uses fewer tokens, while the private-store guide reduces seed exposure in one session.

## Scoped candidate by task

The scoped guide preserves every expected file in each repeat.

| Task | Control total tokens | Kast total tokens | Control/Kast | Correct in each arm |
|---|---:|---:|---:|---:|
| `references.text-match` | 196,091 | 48,341 | 4.06× | 2/2 |
| `references.retention` | 74,807 | 31,030 | 2.41× | 2/2 |
| Combined | 270,898 | 79,371 | 3.41× | 4/4 |

Tokens per correct task decrease from **67,724.5** to **19,842.75**.

An exact paired task-cluster bootstrap gives an exploratory 95% ratio interval of **2.41×–4.06×**.

The calculation enumerates four samples from only two distinct task clusters and keeps both repeats within each cluster.
This small interval calculation does not establish population performance or holdout acceptance.
The 5× target needs at most 54,179.6 tokens for the same four correct tasks.
The candidate needs a further **31.7%** decrease from its current total to reach that threshold.

Final-answer equality does not establish the correctness of every intermediate semantic choice.
The declaration audit supports issued seed identity, but it does not adjudicate every relation result.
The `semantic_misselections` and `false_completeness` counts remain unknown.
No result establishes accuracy across other operation families, repositories, or harder tasks.

## Intermediate output

Native Pi `codemode` keeps nested tool results outside model context.
Only the script output reaches the model.
Its native `store()` and `load()` functions preserve private values across successful scripts.
The experiment reuses those primitives and the existing native MCP extension.
It adds no integration framework or packaged skill.

Actual sessions still print seed references despite the instructions.
The telemetry finds seed exposure in all four scoped sessions and three private-store sessions.
Its scope covers known issued seed strings in `codemode` text output.
It does not certify the absence of other intermediate content.
Unsupported or unfinished seed evidence produces `UNKNOWN`.

The guides preserve a source review instead of treating a relation response as the complete source-file oracle.

The instruction-only approach lacks an enforced limit on model-visible output.
A strict privacy claim needs a host output policy that preserves private continuation facts and exposes incomplete or rejected evidence.
That policy needs separate implementation and real-session evidence before promotion.

## Provider timing

The existing session journal now includes these optional facts on each `assistant_end` event:

- Pi `durationMs`.
- Client time of response headers and the first observable text, reasoning, or tool-call delta.
- Provider `created_at` and `completed_at` from the same terminal stream event.
- The numeric `openai-processing-ms` header, when the provider supplies it.

The projector keeps only these numeric fields.
It does not record other headers or response bodies.
Missing or malformed optional metadata does not erase valid client timing.

The reader pairs each admitted request with one finalized response.
It asserts serial identity, monotonic intervals, finalized token totals, response counts, and output totals.
A missing terminal response makes the task total unavailable and preserves only its qualified completed prefix.
Nested tool intervals use their union, so overlapping calls do not count twice.
The reader uses worker wall time for the provider and other-time split.
Parent startup, final source assertions, and cleanup remain in the separate end-to-end task time.

| Scoped comparison, four tasks | Control | Kast |
|---|---:|---:|
| Provider output tokens, including reasoning | 3,741 | 7,332 |
| Client provider span, seconds | 115.35 | 153.30 |
| Other worker time, seconds | 2.80 | 9.46 |
| End-to-end task time, seconds | 121.87 | 166.30 |
| Output tokens per client provider second | 32.43 | 47.83 |
| Output tokens per provider timestamp second | 38.97 | 52.00 |

Rates divide summed output tokens by summed seconds.
They do not average per-response rates.
Client spans include network, queue, prompt processing, response generation, and event-delivery time.
Server timestamps keep the provider precision, which is one second in these runs.
Neither rate establishes pure decode speed or provider hardware performance.
A zero server span has no defined per-response rate.
No fixed-rate normalization invents a provider-independent latency.

The scoped candidate reduces total tokens but generates more output tokens and takes more time.
The report keeps that regression visible.
Tokens per correct task remain the optimization metric.
Provider rates and the other-time split explain timing without attributing the difference to workflow changes alone.
Billed dollar cost remains unavailable for this subscription provider.

## Artifacts and replay

The private study parent is `/Users/amichne/.local/state/kast-agent-efficiency/studies`.
The measured configurations use these two directories:

- `reference-chain-scoped-813691d`
- `reference-chain-private-813691d`

Each study's `candidate/` directory keeps its manifest, trial records, journals, and controller source.
The private-store study also keeps `retained-configurations.json` and the real CLI proof.
The scoped study keeps a real provider tracer and its parsed timing result.
The tracer records nine output tokens over a 3.0317-second client span and a two-second server span.
Its provider processing header remains unavailable.

Restore the recorded producer and subject revisions before inference replay.
Keep the archived instruction bytes and source assertions unchanged.
Use a new output directory for each replay.
The current checkout head does not match the recorded subject after these experiment commits.

Read an existing run with the current report CLI:

```sh
STUDY=/Users/amichne/.local/state/kast-agent-efficiency/studies/reference-chain-scoped-813691d
uv run --locked --project experiments/host-observation python \
  experiments/host-observation/agent_efficiency_tokens.py \
  "$STUDY/candidate" --output /private/tmp/new-reference-timing-report.json
```

The CLI rejects an existing output path.
Older journals still yield client spans when their request and terminal events survive.
Absent provider metadata stays unavailable.

## Validation

- **Passed:** 183 Python cases, 182 `subtests`, strict Pyright, Ruff lint, and Ruff formatting.
- **Passed:** 19 Node cases for session, usage, timing projection, and exposure evidence.
- **Passed:** The real CLI preserves literal paths and matches independent totals and journal-derived rates.
- **Passed:** The CLI rejects an existing output and missing input.
- **Passed:** A removed terminal response keeps a qualified prefix and rejects an exact task-time total.
- **Passed:** `verifyJsonContracts`, `knowledgeImpact`, `verifyKnowledgeBase`, and `hostObservationTest`.
- **Passed:** A real Pi tracer supplies the new provider timing fields.
- **Not established:** Broader correctness, complete semantic audits, held-out gains, and the 5× target.

Pinned prose reports accompany the study artifacts.
Frozen measured briefs keep their exact bytes, including unresolved lexical findings.
An **ASSESSED** prose result does not establish source truth or reader comprehension.

Task totals exclude preparation and coordinator work.
