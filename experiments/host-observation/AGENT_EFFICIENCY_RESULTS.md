# Kast agent efficiency: first iteration

The 5× target is unmet.
No configuration qualifies for promotion.
The strongest development profile used native MCP with `onboarding` set to `none`.
It used zero Kast tools in development and holdout trials.
These results do not establish a gain from Kast semantic operations.

## Source and evidence

The checkout starts from `origin/main` at `5937ff1f46fbb0914c36226afb52426a4271c29c`.
The branch is `experiment/pi-efficiency-optimization`.
The experiment uses Pi 1.3.0 with `openai-codex/gpt-6.1-sol` and `high` effort.
Both arms use the same tools, permissions, limits, source, and fixed output rules.
Each trial owns a fresh process and session.

Provider caches remain shared and warm.
Trial order alternates the arms.

The corpus contains 132 tasks across 11 families.
It reserves 33 holdout tasks before optimization.
The development comparison uses 11 tasks with two repeats per arm.
The final holdout comparison uses all 33 tasks once per arm.
Four corpus cases lack qualified freshness oracles.
Two such cases occur in the holdout and cannot grant correctness.
Their inference cost and elapsed time remain in the totals.

The corpus covers one checkout with 3,189 tracked Kotlin files and 461,624 tracked Kotlin lines.
File, module, and workspace scopes do not establish several repository scales.
The 33-task holdout does not meet the active goal's 100-task minimum.

Most tasks ask source questions or restore a controlled source defect.
They do not establish broad compiler, runtime, or live freshness coverage.

## Paired results

Each ratio compares the treatment with its control.
A value greater than 1 favors the treatment.
The intervals use 10,000 paired bootstrap samples with seed 917.
Each task cluster keeps both arms and every repeat.

| Configuration | Task runs per arm | Correct per arm | Correct tasks per hour | Correct tasks per catalog dollar |
|---|---:|---:|---:|---:|
| Canonical brief, development | 22 | 22 | 0.54× [0.43, 0.69] | 0.25× [0.22, 0.29] |
| Native MCP, no extra brief, development | 22 | 22 | 1.06× [0.83, 1.30] | 1.02× [0.86, 1.23] |
| Native MCP, no extra brief, holdout | 33 | 31 | 0.94× [0.79, 1.09] | 0.96× [0.82, 1.12] |

The holdout control completed 439 correct tasks per hour.
The treatment completed 412 correct tasks per hour.
The control's median latency was 6.66 seconds, with p90 of 10.64 and p95 of 11.83 seconds.
The treatment's median latency was 6.30 seconds, with p90 of 13.46 and p95 of 14.90 seconds.
The median time ratio was 1.06× [0.90, 1.25].
The median catalog-cost ratio was 0.94× [0.71, 1.22].

The holdout control used 196,969 reported tokens.
The treatment used 205,076 reported tokens.

| Catalog estimate | Control | Treatment |
|---|---:|---:|
| Total USD | 0.2718 | 0.2836 |
| USD per correct task | 0.00877 | 0.00915 |
Pi supplied catalog-priced estimates.
The runner did not capture actual billed dollars.

The `semantic_misselections` and `false_completeness` fields remain unknown without an independent audit.
The metric module preserves those unknown values and refuses acceptance.
The small corpus does not establish the goal's statistical accuracy criterion.

## Holdout families

Each family contains three cases.
The two unqualified cases belong to freshness.
The ratios include every case's elapsed time and estimated cost.

| Family | Control correct | Treatment correct | Throughput ratio | Catalog efficiency ratio |
|---|---:|---:|---:|---:|
| changes | 3/3 | 3/3 | 0.85× | 0.71× |
| diagnostics | 3/3 | 3/3 | 0.90× | 0.47× |
| discovery | 3/3 | 3/3 | 0.59× | 1.15× |
| freshness | 1/3 | 1/3 | 1.08× | 0.89× |
| impact | 3/3 | 3/3 | 1.09× | 1.06× |
| negative | 3/3 | 3/3 | 0.94× | 1.13× |
| relationships | 3/3 | 3/3 | 1.65× | 1.02× |
| resolution | 3/3 | 3/3 | 0.91× | 1.40× |
| source | 3/3 | 3/3 | 1.12× | 0.99× |
| traversal | 3/3 | 3/3 | 0.82× | 0.94× |
| verification | 3/3 | 3/3 | 0.89× | 1.10× |

## Attributable changes

The no-brief profile improved development throughput by 2.22× [1.84, 2.60] relative to the canonical treatment.
It improved catalog-priced efficiency by 4.00× [3.54, 4.60] relative to that treatment.
This change removed duplicate context and unnecessary tool use on these source questions.
It did not improve held-out performance against the control.
It is an experimental profile, not a promoted production configuration.

A separate declaration-lookup pilot repeated each ablation three times.
Selective reference reads reduced tokens from about 34,778 to 17,205 per trial.
Their mean latency increased from 10.85 to 14.16 seconds.
Direct exposure reduced provider requests from three to two.
Its mean latency decreased to 9.32 seconds, but estimated cost increased by about 19%.
Neither pilot justifies a general agent-level improvement claim.

No optional skills contributed to these runs.

## Harness repairs and scope

The runner reuses Pi's native MCP support and the existing process controller.
It keeps canonical tool identities and schemas.
The profiles control `onboarding`, `exposure`, and explicit experimental `skills` paths.
The candidate comparison script needs more work for automatic proposals and qualified promotion.

The private `KAST_EVALUATION_DEADLINE_MS` variable caused the initial native rejection in controlled contrasts.
The runner removes that variable before native MCP startup.
An explicit Pi shutdown event ends the native MCP process.

The repaired runner preserves finalized usage after a missing terminal report.
It restores setup changes after an early failure and audits new files.
It examines source and corpus commitments before inference.
Its sandbox denies reads and writes to oracle files.

Its provider guard uses explicit abort because Pi catches hook exceptions.
A live negative case stopped at one provider request and recorded the second request's denial.
This case proves the client behavior, without a backend billing claim.

The holdout preceded the final provider-guard repair.
Its observed maximum was four requests against a limit of twenty.
Focused assertions preserve the accepted request shape.
The full holdout did not repeat after that repair.
The raw receipts and controller snapshots preserve these versions separately.

## Executed checks

- **Passed:** 56 focused Python cases and 175 `subTest` cases.
- **Passed:** seven Node cases for session admission and provider limits.
- **Passed:** strict Pyright and Ruff for the authored Python files.
- **Passed:** the locked script's real `--help` command.
- **Passed:** `mise exec -- ./gradlew --max-workers=2 verifyJsonContracts knowledgeImpact verifyKnowledgeBase hostObservationTest`.
- **Passed:** 321 host-observation tests, 628 JSON fingerprints with no violations, and 65 knowledge concepts with no citation issues.
- **ASSESSED:** the guide's Vale 3.24.0 report keeps its grade advisory for necessary technical terms.
- **Not run:** a full Kotlin product build or a multi-repository native acceptance suite.

## Continued work

The next corpus needs compiler-dependent tasks, qualified live freshness cases, and several actual repository scales.
It also needs at least 100 sealed holdout tasks for the active goal.
A dollar conclusion needs a declared price oracle or captured billing evidence.
An accuracy conclusion needs independent semantic audits and the stated statistical evidence.
The current measurements establish neither 5× threshold.
No merge, release, or deployment occurred.

The private receipts, source snapshots, corpus, and reassessment script reside at:

`/Users/amichne/.local/state/kast-agent-efficiency/2026-10-19-5937ff1f46-lt2pnir`

The machine summary is [final-statistics.json](/Users/amichne/.local/state/kast-agent-efficiency/2026-10-19-5937ff1f46-lt2pnir/final-statistics.json).
The operating guide is [AGENT_EFFICIENCY.md](AGENT_EFFICIENCY.md).

The [native development pilot](AGENT_EFFICIENCY_NATIVE_RESULTS.md) records the next iteration.
