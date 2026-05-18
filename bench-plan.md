# Benchmark Reliability Repair Plan

## Summary
Make the benchmark pipeline reject infrastructure-tainted runs before scoring, require positive evidence for positive claims, surface token cost, and fix the Copilot SDK hook startup failure. The goal is that a future run can distinguish “skill underperformed” from “runner did not produce valid evidence.”

## Key Changes
- Add run-integrity gates in `finalize_grading.py` and `value_proof_aggregate.py`:
  - New invalid reasons in `evaluation/benchmark.schema.json`: `executor_failed`, `empty_transcript`, `hook_error`.
  - Mark a run invalid when `timing.status != "succeeded"`, `last_exit_code != 0`, transcript is empty, or SDK events contain hook/session errors.
  - Preserve executor failure details in aggregate `integrity`: status, exit code, short message, transcript presence, hook error count, session error count.
- Tighten deterministic grading in `script_grader.py`:
  - Empty assistant-visible output must fail all outcome/process expectations except explicitly `not_applicable`.
  - Replace “no negative evidence phrase found” passes with positive evidence checks for mutation/compile/scope expectations.
  - Keep precision-only checks valid only when there is non-empty answer content.
- Fix Copilot SDK benchmark hook wiring in `run-one.mjs`:
  - Replace incompatible `onSessionStart` hook shape with the SDK-supported form, or remove it if it is only advisory.
  - Treat hook errors from `sdk-events.jsonl` as benchmark-invalid until the runner proves they are harmless.
- Add token reporting:
  - Include `input_tokens`, `output_tokens`, `cache_read_tokens`, and `total_tokens` in `benchmark.json` summaries and `benchmark.md` / `executive-summary.md`.
  - Use `run-artifacts.mjs` token extraction as the source of truth, not `timing.json`’s current zero placeholder.
- Add a preflight guard before dispatch:
  - Fail fast if available disk space is below a conservative threshold needed for all planned worktrees and transcripts.
  - Report this as a benchmark setup failure, not as per-run model behavior.

## Test Plan
- Add red tests first for:
  - Failed `timing.json` produces `invalid_reason=executor_failed`.
  - Empty transcript produces `invalid_reason=empty_transcript` and no passing evidence-only expectations.
  - SDK hook/session error produces `invalid_reason=hook_error`.
  - Token metrics appear in aggregate and markdown summaries.
- Add regression fixtures based on `mock-single-20260518T173902Z`:
  - A failed `No space left on device` run must be excluded.
  - A zero-transcript run must not pass mutation/scope expectations.
  - A valid semantic-tool run still scores normally.
- Validate with the narrowest evaluation test suite, then run one small mock benchmark after disk preflight passes.

## Assumptions
- Do not change the benchmark catalog’s task intent in this pass.
- Do not attempt to improve skill behavior yet; this pass only repairs measurement trust.
- Treat any runner/hook/session failure as invalid until there is explicit evidence it cannot affect tool availability or transcript capture.
