# JSON schemas

These are the durable data shapes used by the model-agnostic evaluation scaffold.

`scripts/quick_validate.py` and the eval helper scripts enforce these shapes programmatically. Treat the file locations below as part of the contract, not loose examples.

## Directory contract

- Durable suite files live in `evals/` and `history/` only.
- Referenced input fixtures must live under `evals/files/`.
- Transient benchmark artifacts belong in a separate workspace (or a root `benchmarks/` / `workspaces/` directory), not beside `SKILL.md`.
- If a skill has `evals/`, it should also have `history/progression.json`.

---

## evals/catalog.json

Canonical eval cases that define the shared suite.

```json
{
  "skill_name": "example-skill",
  "version": 3,
  "cases": [
    {
      "id": "missing-axis-labels",
      "title": "Chart includes axis labels",
      "prompt": "Build a revenue chart from evals/files/revenue.csv with clear axis labels.",
      "files": ["evals/files/revenue.csv"],
      "expected_output": "A chart with labeled axes and readable units.",
      "expectations": [
        "The x-axis is labeled",
        "The y-axis is labeled",
        "The chart uses the provided revenue data"
      ],
      "labels": ["charts", "regression"],
      "stage": "candidate",
      "source": {
        "kind": "copilot_event_log",
        "session_id": "75025ff8-c15b-4327-9d8a-eb21ae184ee2",
        "interaction_id": "e361f5c1-caf2-446c-ada1-bad62c74003d",
        "summary": "User had to ask for axis labels after the first result"
      },
      "promotion": {
        "required_pass_rate": 1.0,
        "required_benchmarks": 2
      }
    }
  ]
}
```

Notes:

- `version` is required and should increase when the catalog changes
- `cases[].stage` should be one of `candidate`, `holdout`, `core`, or `retired`
- `cases[].files` must only reference files under `evals/files/`
- `cases[].source.kind` is required
- `promotion` controls when a case can move to the next stage
- `source` should preserve where the case came from so additions stay auditable

---

## evals/pain_points.jsonl

Raw intake queue for new issues. One JSON object per line.

```json
{
  "id": "75025ff8:e361f5c1:followup",
  "title": "Axis labels missing",
  "summary": "the chart is missing axis labels",
  "labels": ["copilot-event-log", "user-followup"],
  "source": {
    "kind": "copilot_event_log",
    "session_id": "75025ff8-c15b-4327-9d8a-eb21ae184ee2",
    "interaction_id": "e361f5c1-caf2-446c-ada1-bad62c74003d",
    "event_path": "/Users/name/.copilot/session-state/.../events.jsonl",
    "user_prompt": "Make me a revenue chart from this CSV"
  },
  "suggested_eval": {
    "prompt": "Make me a revenue chart from this CSV",
    "files": ["evals/files/revenue.csv"],
    "expected_output": "A chart with axis labels",
    "expectations": [],
    "labels": ["user-followup"]
  }
}
```

`scripts/merge_pain_points.py` turns these into `candidate` entries in `evals/catalog.json`.
Each record should include `source.kind` and a `suggested_eval` payload so the merge step can stay deterministic.

---

## normalized sessions JSON

Output from `scripts/ingest_copilot_events.py`.

```json
{
  "generated_at": "2026-04-28T17:42:00Z",
  "source_count": 12,
  "sessions": [
    {
      "session_id": "75025ff8-c15b-4327-9d8a-eb21ae184ee2",
      "event_path": "/Users/name/.copilot/session-state/.../events.jsonl",
      "cwd": "/Users/name/project",
      "models": ["model-a", "model-b"],
      "skill_invocations": [
        {
          "name": "skill-creator",
          "path": "/Users/name/.agents/skills/skill-creator/SKILL.md",
          "timestamp": "2026-04-28T16:40:10Z"
        }
      ],
      "turns": [
        {
          "interaction_id": "e361f5c1-caf2-446c-ada1-bad62c74003d",
          "prompt": "Make me a chart from this CSV",
          "transformed_prompt": "...",
          "followups": ["the chart is missing axis labels"],
          "attachments": [{"type": "file", "path": "..."}],
          "tool_requests": [{"name": "view", "arguments": {"path": "..."}}],
          "tools": [
            {
              "tool_call_id": "call_123",
              "name": "view",
              "arguments": {"path": "..."},
              "success": true,
              "timestamp": "2026-04-28T16:40:12Z",
              "model": "gpt-5.4",
              "summary": "..."
            }
          ],
          "assistant_messages": [],
          "assistant_text": "",
          "signals": {
            "tool_failures": 0,
            "followup_pain_signal": true
          }
        }
      ],
      "pain_points": []
    }
  ]
}
```

Use this as the durable transcript adapter output. It is intentionally richer than the eval catalog so future tooling can derive new views from it.

---

## history/progression.json

Ledger of accepted and rejected benchmark decisions.

```json
{
  "skill_name": "example-skill",
  "updated_at": "2026-04-28T17:42:00Z",
  "benchmarks": [
    {
      "benchmark_path": "/repo/example-skill-workspace/iteration-003/benchmark.json",
      "timestamp": "2026-04-28T17:40:00Z",
      "primary_configuration": "with_skill",
      "accepted": true,
      "reasons": [
        "Accepted: core cases and holdout coverage did not regress."
      ],
      "stage_summary": {
        "candidate": {"count": 3, "mean_pass_rate": 0.67, "min_pass_rate": 0.0},
        "holdout": {"count": 2, "mean_pass_rate": 1.0, "min_pass_rate": 1.0},
        "core": {"count": 4, "mean_pass_rate": 1.0, "min_pass_rate": 1.0}
      },
      "promotions": [
        {
          "case_id": "missing-axis-labels",
          "from": "candidate",
          "to": "holdout",
          "pass_rate": 1.0
        }
      ]
    }
  ],
  "case_history": {
    "missing-axis-labels": {
      "stage": "holdout",
      "qualifying_streak": 0,
      "last_pass_rate": 1.0,
      "accepted_pass_rate": 1.0,
      "last_accepted_benchmark": "/repo/example-skill-workspace/iteration-003/benchmark.json"
    }
  }
}
```

When a skill has a persistent eval suite, this file is required. It is the non-regression proof trail, not optional bookkeeping.

---

## eval_metadata.json

Per-run metadata kept beside an eval directory.

```json
{
  "eval_id": "missing-axis-labels",
  "eval_name": "Chart includes axis labels",
  "prompt": "Build a revenue chart from evals/files/revenue.csv with clear axis labels.",
  "assertions": [
    "The x-axis is labeled",
    "The y-axis is labeled"
  ]
}
```

Use the same `eval_id` as `catalog.json` so benchmarks can be joined back to the suite.
Each eval directory in a benchmark workspace should carry this file so the benchmark and viewer can resolve stable eval identity.

---

## grading.json

Output from the grader.

```json
{
  "expectations": [
    {
      "text": "The x-axis is labeled",
      "passed": true,
      "evidence": "Observed 'Month' beneath the horizontal axis in chart.png"
    }
  ],
  "summary": {
    "passed": 3,
    "failed": 0,
    "total": 3,
    "pass_rate": 1.0
  },
  "execution_metrics": {
    "tool_calls": {"view": 4, "bash": 2},
    "total_tool_calls": 6,
    "total_steps": 4,
    "errors_encountered": 0,
    "output_chars": 2500,
    "transcript_chars": 1800
  },
  "timing": {
    "executor_duration_seconds": 24.3,
    "grader_duration_seconds": 6.1,
    "total_duration_seconds": 30.4
  }
}
```

The viewer expects `expectations[].text`, `expectations[].passed`, and `expectations[].evidence`.
The benchmark and review tooling should reject grading outputs that omit these fields.

---

## benchmark.json

Aggregated benchmark output.

```json
{
  "metadata": {
    "skill_name": "example-skill",
    "skill_path": "/repo/example-skill",
    "executor_model": "gpt-5.4",
    "analyzer_model": "gpt-5.4",
    "timestamp": "2026-04-28T17:40:00Z",
    "evals_run": ["missing-axis-labels"],
    "runs_per_configuration": 3
  },
  "runs": [
    {
      "eval_id": "missing-axis-labels",
      "configuration": "with_skill",
      "run_number": 1,
      "result": {
        "pass_rate": 1.0,
        "passed": 3,
        "failed": 0,
        "total": 3,
        "time_seconds": 24.3,
        "tokens": 4100,
        "tool_calls": 6,
        "errors": 0
      },
      "expectations": [],
      "notes": []
    }
  ],
  "run_summary": {
    "with_skill": {
      "pass_rate": {"mean": 1.0, "stddev": 0.0, "min": 1.0, "max": 1.0},
      "time_seconds": {"mean": 24.3, "stddev": 1.1, "min": 23.4, "max": 25.5},
      "tokens": {"mean": 4100, "stddev": 120, "min": 3980, "max": 4220}
    },
    "without_skill": {
      "pass_rate": {"mean": 0.33, "stddev": 0.12, "min": 0.0, "max": 0.5},
      "time_seconds": {"mean": 18.2, "stddev": 0.9, "min": 17.3, "max": 19.1},
      "tokens": {"mean": 2600, "stddev": 90, "min": 2510, "max": 2690}
    },
    "delta": {
      "pass_rate": "+0.67",
      "time_seconds": "+6.1",
      "tokens": "+1500"
    }
  },
  "notes": [
    "Without-skill runs consistently missed axis labels."
  ]
}
```

`scripts/progression_gate.py` reads this file and uses the selected primary configuration as the candidate being judged.
For consolidation work, include the merged candidate and each legacy sibling as separate configurations in the same benchmark so follow-on proof tooling can compare them on the combined eval set.

---

## overlap_report.json

Collection-level overlap audit output from `scripts/audit_skill_overlap.py`.

```json
{
  "skills_root": "/repo/.github/skills",
  "generated_at": "2026-05-01T20:15:00Z",
  "skill_count": 16,
  "findings": [
    {
      "skill_a": "defect-rca",
      "skill_b": "jira-rca-comment",
      "score": 0.58,
      "weighted_overlap": 0.49,
      "description_overlap": 0.53,
      "name_overlap": 0.27,
      "name_similarity": 0.31,
      "shared_terms": ["rca", "jira", "defect", "ticket"],
      "shared_high_signal_terms": ["rca", "jira", "defect", "ticket"]
    }
  ]
}
```

Use this report to audit existing sibling overlap clusters before deciding whether to create, split, or merge a skill.

---

## consolidation_report.json

Proof artifact from `scripts/prove_consolidation.py`.

```json
{
  "candidate_configuration": "consolidated_skill",
  "baseline_configurations": ["legacy_alpha", "legacy_beta"],
  "max_pass_rate_regression": 0.0,
  "consolidation_supported": true,
  "verdict": "supported",
  "summary": {
    "evaluated_cases": 4,
    "improved_cases": 1,
    "matched_cases": 3,
    "regressed_cases": 0,
    "candidate_mean_pass_rate": 0.92,
    "legacy_envelope_mean_pass_rate": 0.90,
    "average_legacy_mean_pass_rate": 0.81,
    "delta_vs_legacy_envelope": "+0.02",
    "delta_vs_average_legacy": "+0.11",
    "candidate_time_seconds_mean": 24.3,
    "average_legacy_time_seconds_mean": 20.1,
    "candidate_tokens_mean": 4100,
    "average_legacy_tokens_mean": 3100
  },
  "case_results": [
    {
      "eval_id": "missing-axis-labels",
      "candidate_pass_rate": 1.0,
      "legacy_envelope_pass_rate": 1.0,
      "best_legacy_configuration": "legacy_alpha",
      "baseline_pass_rates": {
        "legacy_alpha": 1.0,
        "legacy_beta": 0.5
      },
      "delta_vs_legacy_envelope": "+0.00",
      "status": "matched"
    }
  ],
  "reasons": [
    "The consolidated candidate matched or exceeded the best legacy pass-rate envelope on every eval."
  ]
}
```

This is the durable answer to "did merging overlapping skills stay non-regressive on the combined trigger space?"

---

## comparison.json

Blind comparison output.

```json
{
  "winner": "A",
  "reasoning": "Output A is more complete and easier to use.",
  "rubric": {},
  "output_quality": {}
}
```

---

## analysis.json

Post-hoc analysis of why one version won.

```json
{
  "comparison_summary": {
    "winner": "A",
    "winner_skill": "path/to/winner/skill",
    "loser_skill": "path/to/loser/skill",
    "comparator_reasoning": "A was more complete."
  },
  "winner_strengths": [],
  "loser_weaknesses": [],
  "instruction_following": {},
  "improvement_suggestions": [],
  "transcript_insights": {}
}
```
