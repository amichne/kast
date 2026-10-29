# CI validation and cache policy

`validation_scope.py` admits four check plans from the exact Git diff.
The selector checks the checkout revision before it reads the diff.
Pull requests use the merge base. Pushes use the entire before/head range.

| Plan | Required work | Product publication |
| --- | --- | --- |
| `DOCUMENTATION` | Mintlify, parser, glossary, knowledge links, and example consumers | None |
| `SOURCE` | Affected owners and consumers, their semantic families, shared guards, offline preflight and portable packaging | None |
| `PRODUCT` | Source gate, product gate, immutable candidate and portable packaging | Admitted tested candidate |
| `FULL` | Every check above and gate membership inspection | Admitted tested candidate |

The documentation plan admits the reviewed pages, navigation, styles, and value-journey example inputs.
Before adding a path, inspect its consumers in Gradle, packaging, generators and tests.
Installer examples in README, start, installation and compatibility keep full validation.
Most OpenWiki source inputs keep full validation.
Reviewed documentation inputs instead run `check-documentation-inputs` for source paths and links.
That mode emits `DOCUMENTATION_INPUTS` evidence and makes no Kotlin declaration claim.
Full source validation keeps `verifyKnowledgeBase` and its declaration evidence.
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

## Validation records and publication

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

## Cache ownership

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

## Temporary semantic fixture switch

Local runs and CI skip the three `semanticCoverageFixtureTest` suites by default.
Their coverage and inventory report consumers also skip.
`config/ci/semantic-fixture-tasks.txt` owns the six excluded task paths.
Ordinary unit tests, dependency archive fixtures, semantic input checks, and product assembly keep their existing selection.
Skipped semantic fixtures provide no compiler behavior evidence for that run.

Set `KAST_SEMANTIC_FIXTURES=true` to enable the suites and report consumers:

```sh
KAST_SEMANTIC_FIXTURES=true mise exec -- ./gradlew productBuildGate
```

Set the GitHub repository variable `KAST_SEMANTIC_FIXTURES` to `true` to enable them in CI.
Set it to `false` to disable them again.
When CI enables the suites, it builds a fresh candidate instead of reusing an earlier candidate with weaker fixture evidence.
Other values reject the Gradle invocation.
Gradle logs the disabled state, and source execution reports list `skippedTasks` under schema version 2.
The switch does not remove fixture sources or their case manifest.

## Main self-hosted runner

The repository variable `KAST_SELF_HOSTED_RUNNER=true` routes the main Kotlin product job to `kast-main` on macOS ARM64.
Only main push and manual main runs use this route.
Pull requests and merge groups keep `macos-15`.
Set the variable to `false` to restore hosted execution for main.

The runner service keeps its checkout under its own work directory.
Its Gradle User Home persists between jobs, so `setup-gradle` disables cache transport on that runner.
Local Python uses the version in `.python-version` through uv and a runner-owned virtual environment.
This avoids the macOS `setup-python` installer's fixed `/Users/runner` prefix.
The native Gradle task passes `-mmacosx-version-min=15.0` to the macOS linker.
Before candidate sealing, it rejects a native binary whose deployment target differs from macOS 15.0.
The local pre-job hook uses `self_hosted_guard.py` before repository steps start.
It rejects other repositories, refs, events, workflows, and jobs.
Install the hook outside the runner application directory.
Set `ACTIONS_RUNNER_HOOK_JOB_STARTED` in the runner's `.env`.
Restart the service after a hook change.
Keep the installed hook synchronized with its reviewed source.

Run the focused checks from the repository root:

```sh
python3 -m unittest discover -s .github/scripts/ci -p 'test_*.py'
python3 .github/scripts/release/test_build_candidate.py
uv run --locked --project docs/tooling pyright --project .github/scripts/ci/pyrightconfig.json
python3 .github/scripts/ci/verify_documentation.py
actionlint .github/workflows/ci.yml .github/workflows/docs.yml .github/workflows/developer-release.yml
```

The decision and rollout modules have a strict Pyright configuration.
The candidate tests keep product provenance checks and exercise explicit no-publication and rejection paths.
Hosted CI must validate the final pull-request head before review completes.

## Source obligations and qualification

`source_validation.py` admits a clean source tree, runs the offline preflight suites, examines the Gradle graph, and runs `sourceValidationGate`.
A failed offline preflight stops before Gradle starts, including its dry-run.
The failure report lists no graph tasks and keeps every source obligation as missing.
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
| Semantic manifest when enabled | Declared cases, exact inventory, input hashes, compiler witnesses, rejection controls |
| Relation and hosted fixtures | Independent copies of the shared dependency archive |
| CLI | JVM native-adapter tests, query delivery contracts and schemas, callable reference, presentation |
| App Server | Public query generation |
| Installation scripts | Offline lifecycle, recovery, removal, cleanup, system Python, local installation and installer fixtures |
| Included build | Build-logic tests and quality rules |
| Offline preflight | Version resolution, product-gate commands, candidate admission, environment admission, gate membership rules and execution evidence |
| Portable container | Onboarding, cloud environment, MCP registration, developer publication, Git attribution, host installation and shared adapter suites |

The graph rejects native compilation, installed-product composition, release runtime admission, hosted-plugin assembly, and release assembly.
Candidate qualification keeps those tasks, actual release versions, acceptance, sealing, and exact identity admission.
Source feedback waits for the selected source, documentation, and portable jobs.
The final required gate also waits for candidate qualification.
A successful source-feedback aggregate cannot seal a qualified-candidate outcome.

Qualification remains necessary for packaging, installation, native inputs, toolchains, and release policy before merge.
Unknown ownership and unavailable history select the full plan.
Repository branch-protection settings remain unchanged.

Source and candidate jobs overlap on release-sensitive changes during this split.
That overlap is measured runner work, not a performance reduction.
The six-minute criterion remains open until the frozen hosted corpus passes.

## Affected obligations and rollout

The source job uses affected obligations after the frozen corpus comparison passes.
The scope selector, source runner, and outcome recorder use the same affected-selection mode.
Main pushes still need candidate qualification before developer promotion.
`source_validation.py --affected --base <full-sha>` admits an exact plan from the current Git diff.
The plan binds the source tree, comparison basis, graph digest, manifest digest, tasks, classes, and case identities.
A supplied `CI_VERIFICATION_PLAN` must equal the recomputed plan.
Unknown paths, stale graph inputs, and missing history keep broader validation or reject the supplied plan.

`generateVerificationGraph` collects project dependencies from all configurations and declared source roots.
The existing `ModuleImpact` owner calculates the consumer closure.
The production-only `topologyImpact` contract stays separate.
`verifyVerificationGraph` compares the generated report with `config/ci/verification-graph.json`.
Source admission examines every recorded input digest before it trusts the graph.
Explicit shared-resource consumers cover query-delivery JavaScript and the host-observation suite.
Hosted protocol consumers include query protocol and CLI checks.

The existing semantic manifest adds `verification_owner` to each case.
Selection keeps each affected owner's complete fixture population, including compiler witnesses and rejection controls.
The independent matrix expects 82 hosted cases for query service and query contract changes.
It expects 230 relation and hosted cases for a relation adapter change.
The seven symbol cases remain in the complete 237-case gate.
`verifySelectedKotlinSemanticCoverage` uses the existing execution accountant for selected owner reports.
Missing, skipped, duplicate, malformed, unexpected, and zero case populations fail that accountant.

The CI dispatch input `rollout` supports `warm`, `changed`, `reuse`, and `cold` phases.
All four corpus jobs use `macos-15`.
Set `rollout_case` to one corpus name for a measurement through its own aggregate.
The default `all` runs the complete matrix.
Each case has a separate concurrency group.
The runner admits only a disposable GitHub checkout.
It reverses and reapplies each frozen patch under two local commits.
The resulting tree must equal the workflow checkout tree.
Full qualification of that exact tree supplies the complete comparison population.

The warm phase runs complete source validation and writes branch-scoped Gradle caches through `setup-gradle`.
Routine pull requests keep their existing read-only cache policy.
The changed phase deletes affected compiler and test outputs before their tasks run.
It disables output-cache reuse for those tasks and preserves normal identity handling for immutable dependency archives.
Unrelated task outputs can use the warmed cache.
The reuse phase permits normal task-cache behavior and has separate evidence.
The cold phase disables Gradle and pip cache transport and removes affected outputs.
Runner-image tools remain available, so setup logs identify the actual provisioning work.
Changed and cold phases keep Gradle cache decisions and dependency details in their logs.
These manual phases end after rollout and do not add duplicate work to routine pull requests.
If selection misses an obligation, remove `--affected` from the scope, source, and outcome commands together.
Remove `CI_VERIFICATION_PLAN` from the source step at the same time.
That rollback restores the complete source path and keeps the independent fixture optimization.

### Interpret rollout measurements

Each measurement records its source identities, exact patch, selected cases, task graph, compiler count, timestamps, and process outcome.
Workflow and job timestamps supply setup, artifact, aggregate, queue, and total runner durations.
The process timer alone cannot establish the six-minute feedback criterion.

The criterion remains open until all four changed-output runs pass within 360 seconds through their required aggregate.
