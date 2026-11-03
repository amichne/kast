# Final integration review

The reviewed baseline is `7de04069e2a4a781a603ff51b3f9016b66108790`.
The integrated checkpoint is `cbf13253648ad7fef8dd977162e507465b344623`.
The review also examined the current prose runner changes and the reader parser repair.

## Scope

The review covered CI selection, exact-candidate admission calls, native update paths, association coverage, reader contracts, prose receipts, and rendered receipts.
It excluded self-approval of the native adapter and publication module, which this reviewer authored.
The coordinator reviews those modules independently.

The manifest covers 46 authored routes and 10 projected routes with 56 associations.
Its reader contracts cover those routes and five generated callable routes.
The callable generator keeps responsibility for those five routes.

## Findings

### R1: Repeated rendered routes can produce inaccurate passed checks

Status: repaired and examined independently.

The renderer compares the number of selected states with the expected count.
It does not compare the complete set of route and state identities.
Five copies of the six passed Overview states produce seven passed representative checks.
The other four representative routes remain absent.

The focused oracle called the real `receipt` function with those states and hashed fixture evidence.
`representative-pages`, `keyboard`, `focus`, `mobile`, `zoom-200`, `themes`, and `screenshots` returned `passed`.
`bounded-measurements` returned `failed`, so the complete receipt still rejected publication.
The repair enforces exact unique route and state coverage for each check.
The negative fixture rejects repeated routes. All 13 renderer fixtures pass.

### R2: Malformed density status escapes the finite failure contract

Status: repaired and examined independently.

The original reader parser raised `TypeError` for dictionary and list values in `densityDisposition.status`.
The negative oracle changed only that field in a copy of the current reader metadata.
The repair places the string guard before set membership.
Both negative cases now return `Failure` with `READER_INPUT`.

Passed: `mise exec -- uv run --locked --project docs/tooling pytest -q -c docs/tooling/pyproject.toml docs/tooling/test_reader_contracts.py -k malformed_density_status`.
The selector ran two cases.

## Other evidence

- Passed: the CI documentation decision script ran nine cases.
- Passed: the reader and prose selectors ran 34 cases before the two new density cases.
- Passed: the final reader, prose, and rendered selectors ran 51 cases after both repairs.
- Passed: the callable component schemas equal the baseline structures.
- Passed: all six operation paths keep their request bodies and response contracts.
- Passed: projector output paths contain 11 approved paths, without engineering instructions or workflow files.
- Passed: native setup preserved an exact 23,755-byte copy of current `AGENTS.md` in a private temporary directory.

The initial mixed-suite command failed during collection because its module paths were absent.
The corrected script command and explicit documentation configuration passed.
That collection failure supplies no product RED evidence.
`final-integration-review-checks.txt` preserves the final command outcomes.

## Limits

This review does not establish a successful product gate, native task completion, reader comprehension, or a live deployment.
The known prose findings and callable rendering failure remain separate acceptance obligations.
The final receipts need the source commit after these repairs.
The candidate cannot claim publication acceptance from this review.

The renderer now emits a separate `candidate-preview-provenance` outcome.
The coordinator must include that outcome in the publication consumer's required names.
This review excludes self-approval of that consumer change.

## Final candidate follow-up

The follow-up examined `cbf13253648ad7fef8dd977162e507465b344623` through the current uncommitted source delta.
`final-candidate-review-source.json` records the source fingerprint and the examined file digests.
This review found a preview ownership defect and an invalid-port failure.
Both repairs passed their decisive cases.

The negative ownership oracle used a real HTTP server and a separate live process without a listener.
The original readiness helper accepted that combination.
The repaired owner now needs exact listener identities, the launched process group, and its ancestor chain.
It rejects an occupied port before launch.
It also examines ownership before and after browser commands and at context completion.
The coordinator admits preview provenance only after that context ends successfully.

Invalid ports `-1`, `0`, and `65536` now return `PREVIEW_ORIGIN_INVALID` through `BrowserFailure`.
The earlier helper raised an untyped `ValueError` for the invalid nonzero values.

The review also found a cleanup gap after a wrapper exited before its descendant.
The repair signals the created session group even after wrapper exit.
Its real fixture keeps a descendant alive after `SIGTERM`, then proves pipe closure after cleanup.
The same fixture proves that an unrelated HTTP server remains alive and ready.
The source and receipt lifetime repair passed independent examination.

Passed: 26 renderer cases and strict Pyright after the ownership and cleanup repairs.
Passed: 88 focused reader, prose, rendered, and publication cases in the final repeat.
`final-candidate-review-checks.txt` preserves that repeat.
The CI workflow now installs `lsof` and `procps` before the rendered procedures.

The 46 changed Claim sidecars keep their 344 Claim IDs and statement values across this review interval.
That comparison supports the prose-only scope. It does not prove semantic equivalence.
The coordinator owns the final association refresh and advisory dispositions.
No further demonstrated defect remains in the reviewed integration changes.

Exact-head receipts, the required repository gates, and hosted CI remain separate acceptance procedures.
The source review does not turn the recorded rendering limit into a passed result.
It does not establish native Kast execution, reader comprehension, merge approval, or a live deployment.
