# CI validation and cache policy

`validation_scope.py` admits four check plans from the exact Git diff.
The selector checks the checkout revision before it reads the diff.
Pull requests use the merge base; pushes use the entire before/head range.

| Plan | Required work | Product publication |
| --- | --- | --- |
| `DOCUMENTATION` | Mintlify, Markdown parser, glossary tests and generated glossary views | None |
| `SOURCE` | Complete source gate and all semantic families | None |
| `PRODUCT` | Source gate, product gate, immutable candidate and portable packaging | Admitted tested candidate |
| `FULL` | Every check above and gate membership inspection | Admitted tested candidate |

Only the authored pages in `DOCUMENTATION_PATHS` can use the documentation plan.
Before adding a path, inspect its consumers in Gradle, packaging, generators and tests.
Installer examples in README, start, installation and compatibility keep full validation.
OpenWiki source inputs and linked pages also keep full validation for edits, deletions and anchor changes.
This includes site navigation, which an OpenWiki concept cites.
A source-inventory test checks the current literal source paths and inline links.
New references to listed pages and unsupported reference forms require review before CI can pass.
Generated schemas, scripts, build configuration, new paths and mixed documentation/product changes also select full validation.
Regular file edits, additions and deletions keep both endpoints of the diff.
Renames are read as deletion plus addition, so an excluded endpoint keeps full validation.
Symlinks, executable modes, type changes and malformed observations select full validation.
Missing history, invalid revisions and unknown events also keep every check.
The source plan accepts main/test Kotlin sources outside the native executable and installation owners.
The product plan covers those release-sensitive owners.
Candidate preflight compares the native owner inventory with the actual native compilation graph.
Changes to build configuration select full validation and must pass that comparison.
Main pushes with source changes select product validation before automatic developer promotion.
A source-inventory test checks that each listed root remains included in `settings.gradle.kts`.
New modules, fixtures and unknown roots keep full validation until their scope is reviewed.

`required_checks.py` admits only an exact plan whose selected jobs succeeded and whose excluded jobs were skipped.
After that aggregate passes, `validation_outcome.py` repeats source selection and seals `validation.json`.
The record retains the repository, source revision, source tree, comparison base, workflow event and run identities.
CI retains it as `ci-validation-outcome-<source>-<attempt>` for 30 days.

Developer promotion requires that record from the exact successful main push run.
A missing, malformed, contradictory or mismatched record rejects promotion.
`SOURCE_VALIDATED` rejects promotion before candidate download.
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
Candidate versions keep the workflow run number.
Source validation uses the stable version `0.0.0` for product, control, and hosted-plugin resources.
Source identities can still invalidate outputs. Measure their effect.
No build scan evidence is available for this change.

Run the focused checks from the repository root:

```sh
python3 -m unittest discover -s .github/scripts/ci -p 'test_*.py'
python3 .github/scripts/release/test_build_candidate.py
uv run --locked --project docs/tooling pyright --project .github/scripts/ci/pyrightconfig.json
python3 .github/scripts/ci/verify_documentation.py
actionlint .github/workflows/ci.yml .github/workflows/docs.yml .github/workflows/developer-release.yml
```

The five decision modules have a strict Pyright configuration.
The candidate tests keep product provenance checks and exercise explicit no-publication and rejection paths.
Hosted CI must validate the final pull-request head before review completes.

## Source obligations and qualification

`source_validation.py` admits a clean source tree, examines the Gradle graph, and runs `sourceValidationGate`.
The script records the source tree, task graph, elapsed time, exit code, and dependency compiler count.
A changed source tree or failed process cannot produce `SOURCE_VALIDATED`.
This local execution record grants no publication authority.
The separate workflow outcome also binds the exact producer and comparison base.

Each Kotlin owner contributes both `scopedCheck` and `check`.
The task graph executes their common dependencies once.
This preserves type-resolved analysis and obligations that `scopedCheck` alone omits.

| Owner | Extra source obligations |
| --- | --- |
| Root | JSON contracts, architecture, configuration ingress, knowledge, documentation, host protocol, component release tests |
| Semantic manifest | All 237 current cases, exact inventory, input hashes, compiler witnesses, rejection controls |
| Relation and hosted fixtures | Independent copies of the shared dependency archive |
| CLI | JVM native-adapter tests, query delivery contracts and schemas, callable reference, presentation |
| App Server | Public query generation |
| Installation scripts | Offline lifecycle, recovery, removal, cleanup, system Python, local installation and installer fixtures |
| Included build | Build-logic tests and quality rules |

The graph rejects native compilation, installed-product composition, release runtime admission, hosted-plugin assembly, and release assembly.
Candidate qualification keeps those tasks, actual release versions, acceptance, sealing, and exact identity admission.
Source feedback waits for the selected source and documentation jobs.
The final required gate also waits for selected candidate and portable jobs.
A successful source-feedback aggregate cannot seal a qualified-candidate outcome.

Qualification remains necessary for packaging, installation, native inputs, toolchains, and release policy before merge.
Unknown ownership and unavailable history select the full plan.
Repository branch-protection settings remain unchanged.

Source and candidate jobs overlap on release-sensitive changes during this split.
That overlap is measured runner work, not a performance reduction.
The six-minute criterion remains open until the frozen hosted corpus passes.
