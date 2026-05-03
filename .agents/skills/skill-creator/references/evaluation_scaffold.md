# Evaluation scaffold

This reference defines the portable evaluation loop for a skill.

## Goals

1. Compare a skill against a baseline or alternate variant.
2. Keep qualitative review and quantitative grading in the same workflow.
3. Add new pain points without weakening the existing suite.
4. Reuse the same structure across models, projects, and teammates.

## Workspace layout

Keep run artifacts outside the skill directory when possible:

```text
<skill-name>-workspace/
└── iteration-001/
    ├── eval-<case-id>/
    │   ├── eval_metadata.json
    │   ├── with_skill/
    │   │   ├── outputs/
    │   │   ├── grading.json
    │   │   └── timing.json
    │   ├── without_skill/
    │   │   ├── outputs/
    │   │   ├── grading.json
    │   │   └── timing.json
    │   └── alternate-model/
    │       ├── outputs/
    │       ├── grading.json
    │       └── timing.json
    ├── benchmark.json
    ├── benchmark.md
    └── feedback.json
```

Use configuration names that describe the comparison clearly:

- `with_skill`
- `without_skill`
- `old_skill`
- `candidate_skill`
- `consolidated_skill`
- `legacy_alpha`
- `legacy_beta`
- `gpt-5.4`
- `model-b`

The benchmark scripts do not require hard-coded names.

Run `python3 scripts/quick_validate.py /path/to/skill` before packaging or promotion so malformed durable eval assets fail early.
Run `python3 scripts/aggregate_benchmark.py <benchmark_dir>` only against workspaces that include `eval_metadata.json` and valid `grading.json` artifacts for every run.
If the benchmark is for a consolidation, follow the aggregate step with `python3 scripts/prove_consolidation.py --benchmark <benchmark.json> --candidate-config consolidated_skill --baseline-config legacy_alpha --baseline-config legacy_beta`.

## Eval case lifecycle

Each case belongs to one stage in `evals/catalog.json`:

| Stage | Purpose |
| --- | --- |
| `candidate` | newly added issue; not yet trusted as a gate |
| `holdout` | trusted enough to block regressions; still proving durability |
| `core` | permanent non-regression set |
| `retired` | preserved for history but not active |

Recommended flow:

1. ingest or write a new pain point
2. merge it into the catalog as `candidate`
3. run benchmarks
4. update `history/progression.json`
5. promote only if the progression gate accepts the benchmark

The durable suite contract is strict:

- `evals/catalog.json`, `evals/pain_points.jsonl`, `evals/files/`, and `history/progression.json` travel together
- `cases[].files` should only point into `evals/files/`
- benchmark JSON, feedback, and review HTML stay in the workspace, not the skill root

## Event-log ingestion flow

Use `scripts/ingest_copilot_events.py` when Copilot session logs are available.

The script should feed two artifacts:

1. normalized session JSON for auditing and reuse
2. candidate pain points JSONL for suite intake

Look for:

- repeated prompts
- follow-up corrections from the user
- failed or retried tool calls
- manual repair steps that recur across sessions
- skill invocations that still required cleanup

These signals are usually stronger than brainstorming hypothetical evals from scratch.

## Grading loop

For each configuration:

1. save the outputs
2. grade them with `agents/grader.md`
3. aggregate results with `scripts/aggregate_benchmark.py`
4. analyze the benchmark with `agents/analyzer.md`
5. open or export the viewer with `eval-viewer/generate_review.py`

Keep the viewer in the loop even when assertions are strong. Human review catches missing expectations and UX regressions that a formal grader can miss.

## Consolidation proof loop

When you are merging overlapping sibling skills, treat "fewer skills" as a maintenance benefit, not as proof by itself.

1. audit the full skills root with `scripts/audit_skill_overlap.py`
2. run the merged skill and each legacy sibling against the union of their eval cases
3. aggregate the benchmark workspace with `scripts/aggregate_benchmark.py`
4. run `scripts/prove_consolidation.py` to compare the merged candidate against the best legacy pass-rate envelope
5. keep `overlap_report.json` and `consolidation_report.json` with the benchmark workspace or review artifact, not in the skill root

The consolidation proof is positive when the merged skill reduces overlap and `consolidation_report.json` shows the candidate matched or exceeded the legacy envelope within the allowed regression tolerance.

## Progression gate

Use `scripts/progression_gate.py` after each benchmark.

The gate should reject a candidate benchmark when:

- any `core` case regresses against the last accepted benchmark
- aggregate `holdout` coverage regresses

The gate should promote a case only when:

- the benchmark was accepted
- the case met its required pass-rate threshold
- it has done so for enough accepted benchmarks in a row

Recommended promotions:

- `candidate -> holdout`
- `holdout -> core`

## Team operating model

For a large team:

1. keep the skill and `evals/catalog.json` in version control
2. let anyone append to `evals/pain_points.jsonl`
3. use the gate to decide promotions
4. treat `history/progression.json` as the proof trail

This avoids two common failure modes:

- adding lots of new evals that never become reliable gates
- claiming improvement by silently removing hard cases

It also makes overlap easier to audit: if a proposed skill is mostly path-scoped or duplicates a sibling skill's trigger space, prefer consolidation or local `AGENTS.md` guidance over another niche skill directory.
