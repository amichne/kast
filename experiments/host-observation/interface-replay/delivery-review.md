# Delivery review repairs

The delivery review found four concrete defects before the first push completed.
The repairs strengthen scope, byte preservation, cohort identity, and source identity.
They change no original task, canonical fixture, product default, provider setting, or measured result.

## Reproductions and repairs

The C3 and C6 graders accepted `includeSubdirectories:false` within the expected directory and source set.
That request excludes descendants that the task includes.
The grader and fixture transitions now reject that narrower scope.
Omitted, null, and true values preserve the production default.
Finish decisions now reject contradictory or unknown fields.
C3 also preserves the issued `CLASS` target when a request selects declaration families.
C6 preserves all four original declaration families because its task forbids a changed query.
The production decoder admits narrower requests, but independent target grades reject them.

The shell concatenated individual byte chunks as strings.
A split within `雪` produced replacement characters without a failure.
Controlled child signals and socket handshakes reproduced the defect without a timing race.
The broker, driver, and captures now keep bounded bytes before a strict UTF-8 decode.
They preserve leading byte-order marks. Invalid bytes reject without data repair.

The driver now reads at most 65,537 `stdin` bytes.
An invalid or oversized input consumes one entry before production admission.
Invalid captures keep their bounded raw bytes as base64.
Exhausted input or output remains incomplete model work. Invalid model bytes remain incorrect model work.
Neither outcome excludes failed work from the correctness denominator.
A driver-only rejection never claims a production parser diagnostic.

The target gate accepted duplicate trial IDs under different repetition labels.
An arithmetic fixture then produced a false `TARGET_MET` result.
Each cohort now rejects duplicate or inconsistent source identities.
Separate baseline and candidate runs can reuse matching IDs.
Legacy receipts can omit `pairId`, but a present value must match the declared case and repetition.

The source guard checked adapters, SDK files, and fixtures without comparing the current checkout with its recorded source identity.
It now rejects source changes and new files before replay creates resources.
Only the owned plan file outside the Git index receives an exclusion.
That exclusion cannot conceal a changed tracked source file.

## Effect on measured evidence

The [regrade audit](delivery-review-regrade.json) binds every calculation to its original receipt hash.
All 96 decisions keep the same correctness, dimension grades, and reasons under the stricter grader.
The four performance assessments also remain identical.
All recorded shell commands, arguments, `stdin`, `stdout`, and `stderr` contain ASCII text.
This fact explains why the repaired UTF-8 path changes no recorded result.
It supplies no proof for arbitrary Unicode. The new executable controls supply that proof for their stated inputs.

The original receipts, provider payloads, source identities, usage, and durations remain unchanged.
The audit is an offline regrade, not new inference.
No new provider request or hidden retry occurred.
The 3× target remains `NOT_MET`.

## Checks

- **PASSED:** 107 deterministic replay Node tests, with 12 optional integrations skipped without explicit SDK and adapter variables.
- **PASSED:** All 32 opt-in integration tests, with zero skips.
- **PASSED:** All 332 host-experiment Python tests.
- **PASSED:** The scope, byte-preservation, identity, and driver-diagnostic counterexamples failed before their repairs and passed afterward.
- **ASSESSED:** Pinned prose reports preserve readability advisories for necessary technical terms.
- **CANCELLED:** The first pre-push product gate stopped after review findings. The branch was not published.

The full pre-push gate and hosted checks must pass for the corrected commit before the merge.
Their terminal results belong to the exact PR head and remain separate from these focused checks.
