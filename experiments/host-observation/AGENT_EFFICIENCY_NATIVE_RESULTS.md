# Kast agent efficiency: native development pilot

This historical report records exploratory repair experiments.
Use [the token POC](AGENT_EFFICIENCY_TOKEN_POC.md) for the current scope and results.
The 5× target remains unmet.
The smaller brief does not qualify for promotion.

A later negative control exposes an incomplete oracle for the bound-reference repair.
A hardcoded result passes that earlier oracle.
The repair counts in this report describe the original assertions, without a claim of full correctness.
The later installation audit resolves the producer revision as `83f4d987a73c07d61250d25ec50a50ceb1826c03`.
Only documentation differs between that producer and the pinned POC source.
The historical next steps and installation-receipt limits describe the earlier investigation.

## Paired results

Four controlled Kotlin repair cases cover callback count, bound references, receiver identity, and policy forwarding.
Each configuration uses two repeats and both arms for each case.
Each arm uses Pi 1.3.0, `openai-codex/gpt-6.1-sol`, and `high` effort.
The compiler/runtime assertions use Kotlin 2.1.21 and Java 25.0.2.
Both arms start from the same complete fixture inputs.
Each run uses a fresh session.

Each ratio compares treatment with control.
The intervals use 10,000 paired bootstrap samples with seed 917.
Each task cluster keeps both arms and both repeats.
Failures remain in the elapsed time and cost evidence.

| Configuration | Control correct | Treatment correct | Throughput ratio | Catalog efficiency ratio |
|---|---:|---:|---:|---:|
| Canonical brief | 8/8 | 7/8 | 0.70× [0.52, 0.88] | Unavailable |
| Smaller brief | 8/8 | 8/8 | 0.59× [0.55, 0.65] | 0.37× [0.35, 0.40] |

The canonical treatment suffered one provider failure with the message `terminated`.
Its cost remains unknown.
The smaller brief used 395,776 reported tokens against the control's 111,342 tokens.
Its median latency was 34.77 seconds against 20.57 seconds.
Its estimated cost per correct task was USD 0.03870 against USD 0.01438.
These catalog estimates do not establish billed dollars.

The smaller brief reduced reported tokens relative to the canonical treatment.
It did not improve throughput relative to that treatment.
Neither configuration improves throughput against its control.

## Eligibility comparison

A later comparison uses separate worktrees and two repeats for the same four development cases.
Each fixture has one Git root commit with the broken input.
Its history contains no correct parent revision.
The reference brief and the eligible brief differ only in mutation eligibility guidance.
No other experimental Pi session or build ran during this comparison.

| Brief | Control correct | Treatment correct | Throughput ratio | Catalog efficiency ratio |
|---|---:|---:|---:|---:|
| Reference | 8/8 | 8/8 | 0.73× [0.57, 0.95] | 0.34× [0.21, 0.51] |
| Eligible | 8/8 | 8/8 | 0.83× [0.69, 0.99] | 0.38× [0.27, 0.50] |

The direct comparison between treatments gives a throughput ratio of 1.26× [1.05, 1.54].
Its catalog efficiency ratio is 1.31× [0.99, 1.89].
The cost interval includes a regression, so this candidate lacks enough evidence for promotion.
Both treatments remain slower and more expensive than their controls.

The eligible brief reduced provider requests from 69 to 54.
It reduced reported tokens from 402,273 to 278,863.
The reference treatment made eight native mutation calls, with six planning rejections.
The eligible treatment made two native mutation calls, without a rejection.
All 32 trials passed the independent compiler/runtime and declaration-scope assertions.
These assertions do not establish a complete semantic audit.

The rejection pattern matches the current source gate in `HostedLiveReplaceBodyCompiler`.
That gate accepts only existing non-inline block bodies without a first Kotlin contract call.
The rejected cases use expression bodies or an inline function.
The installed payload lacks a source revision receipt, so this match does not prove exact source identity.

The paired intervals use 10,000 task-cluster samples with seed 917.
Only four independent task clusters support these estimates.
The evidence remains developmental.
The full manifest, snapshots, journals, assertions, and comparisons reside in this directory:

```text
/Users/amichne/.local/state/kast-agent-efficiency/studies/eligible-body-5937ff1-v3
```

## Independent evidence

The curator qualified twelve development cases with real compiler/runtime pairs.
Each valid case passed.
Each nearest regression compiled and then failed its named runtime assertion.
Four cases also passed the pilot's own positive and negative preflight.
An independent assertion protects source outside the selected declaration.

A separate compiler-IR tracer distinguishes an `Int` overload, a `String` overload, and no-caller controls.
Its complete fixture contains three source files and seven named functions.
It records one anonymous call without attribution to the enclosing named function.
Six parser and adapter tests passed.
This tracer does not establish a general Kotlin graph oracle.

The earlier mutation tracer completed a native Pi query, a Kast mutation, and an independent runtime assertion.
It changed only the selected declaration.
It supplies integration evidence without a matched performance claim.

## Harness repairs

A cold native query added four missing Gradle wrapper files in the first pilot.
The second pilot supplies the complete wrapper inputs to both arms.
The interrupted first pilot remains outside acceptance evidence.

The runner separates the producer's references from the subject workspace.
It supports scoped behavioral repairs without a byte-identical source requirement.
It records command outcomes, file changes, and bounded native metadata.
Its metadata projector preserves Pi's native MCP envelope and hashes issued references.
It omits source bodies and replacement text.
Unknown projections do not establish a complete semantic audit.

The accuracy rule now uses a conservative one-sided bound on harmed task clusters.
Repeats do not count as independent tasks.
Under this rule, zero harmed tasks need 149 independent tasks to meet the 2% margin.
The four-case pilot cannot pass `accuracy_noninferiority_95`.

## Executed checks

- **Passed:** 100 focused Python cases and 182 `subTest` cases.
- **Passed:** 16 Node cases for native metadata, session admission, and limits.
- **Passed:** strict Pyright and Ruff for the public Python harness.
- **Passed:** 326 host-observation tests and the JSON, knowledge-impact, and knowledge guards.
- **Passed:** six tests for the closed compiler-IR tracer.
- **Passed:** 18 curator cases.
- **Skipped:** one historical Gradle preflight selector, without its explicit native opt-in.
- **ASSESSED:** Vale 3.24.0 and the pinned prose assessment keep grade advisories for technical terms.
- **Not run:** a full Kotlin product build or a qualified final holdout comparison.

## Limits and continuation

These development fixtures do not constitute a representative held-out corpus.
The pilot uses shared warm caches and includes native preparation in the elapsed time.
Compiler-oracle work and local assertions also occurred during this exploratory pilot.
Both profiles reused each fixture in sequence.
A final comparison needs separate worktrees, a fixed adapter, and isolated measurement conditions.
Independent semantic audits, native work counters, and billed dollars remain incomplete.

The installed payload reports version `0.0.3637`.
All 13,699 current payload files match its installation receipt.
That receipt supplies no Git revision for the payload.
The source checkout remains `5937ff1f46fbb0914c36226afb52426a4271c29c`.
The current payload examination does not establish historical bytes or the loaded IDE plugin's bytes.

The next experiment will remove redundant discovery when the brief supplies the needed basic query shape.
A separate candidate will use the known package before a guessed source directory.
The corpus also needs broader compiler-dependent cases and a sealed holdout.
No merge, release, or deployment occurred.

The private evidence resides in `native-development-v2` under the experiment archive.
The archive path appears in [the first report](AGENT_EFFICIENCY_RESULTS.md).
