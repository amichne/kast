# CI validation and cache policy

`validation_scope.py` admits three check plans from the exact Git diff.
The selector checks the checkout revision before it reads the diff.
Pull requests use the merge base; pushes use the entire before/head range.

| Plan | Required work | Product publication |
| --- | --- | --- |
| `DOCUMENTATION` | Mintlify, Markdown parser, glossary tests and generated glossary views | None |
| `PRODUCT` | Product gate, immutable candidate and portable packaging | Admitted tested candidate |
| `FULL` | Every check above and gate membership inspection | Admitted tested candidate |

Only the authored pages in `DOCUMENTATION_PATHS` and the listed site navigation file can use the documentation plan.
Before adding a path, inspect its consumers in Gradle, packaging, generators and tests.
Installer examples in README, start, installation and compatibility keep full validation.
Generated schemas, scripts, build configuration, new paths and mixed documentation/product changes also select full validation.
Regular file edits, additions and deletions keep both endpoints of the diff.
Renames are read as deletion plus addition, so an excluded endpoint keeps full validation.
Symlinks, executable modes, type changes and malformed observations select full validation.
Missing history, invalid revisions and unknown events also keep every check.

`required_checks.py` admits only an exact plan whose selected jobs succeeded and whose excluded jobs were skipped.
After that aggregate passes, `validation_outcome.py` repeats source selection and seals `validation.json`.
The record retains the repository, source revision, source tree, comparison base, workflow event and run identities.
CI retains it as `ci-validation-outcome-<source>-<attempt>` for 30 days.

Developer promotion requires that record from the exact successful main push run.
A missing, malformed, contradictory or mismatched record rejects promotion.
`PRODUCT_VALIDATED` retains the existing candidate, source-tree and payload admission checks.
`DOCS_VALIDATED` succeeds without a candidate or a promotion record and leaves the developer product pointer unchanged.
It never labels an earlier binary with the documentation revision.
The documentation job runs parser and glossary checks directly; the full product gate also retains those proofs.
That duplication adds unmeasured work to full runs.

Gradle cache transport remains owned by the pinned `setup-gradle` action.
Only main push and main workflow-dispatch runs can write entries.
Pull requests, forks, merge groups and other refs remain read-only.
This prevents PR-scoped writes from consuming space that shared main entries need.
Cleanup runs after successful builds, and existing encrypted configuration-cache handling stays in place.

A successful main candidate reuse skips Gradle setup and retains the existing fast path.
When main has no useful Gradle cache, an authorized CI workflow dispatch on an accepted main revision can seed it.
That dispatch runs full validation and does not trigger automatic developer publication.
Do not change candidate reuse merely to refresh a cache.
Keep setup restore/save reports, build task outcomes, native metrics and job timestamps from necessary runs.
A transport hit does not prove task-output reuse or a speedup.
Run-number-derived versions and source identities can invalidate outputs; measure their effect.
No build scan evidence is available for this change.

Run the focused checks from the repository root:

```sh
python3 -m unittest discover -s .github/scripts/ci -p 'test_*.py'
python3 .github/scripts/release/test_build_candidate.py
uv run --locked --project docs/tooling pyright --project .github/scripts/ci/pyrightconfig.json
python3 .github/scripts/ci/verify_documentation.py
actionlint .github/workflows/ci.yml .github/workflows/docs.yml .github/workflows/developer-release.yml
```

The four decision modules have a strict Pyright configuration.
The candidate tests keep product provenance checks and exercise explicit no-publication and rejection paths.
Hosted CI must validate the final pull-request head before review completes.
