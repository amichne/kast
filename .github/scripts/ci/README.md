# CI validation and cache policy

`validation_scope.py` selects checks from the exact Git diff and the native compilation setting.
The selector checks the checkout revision before it reads the diff.
Pull requests use the merge base. Pushes use the entire before/head range.

| Plan | Required work | Product publication |
| --- | --- | --- |
| `DOCUMENTATION` | Mintlify, parser, glossary, knowledge links, installer examples, and documentation tooling | None |
| `SOURCE` | Affected owners and consumers, their semantic families, shared guards, offline preflight and portable packaging | None |
| `SOURCE_FULL` | Complete source gate, portable packaging and documentation | None |
| `PRODUCT` | Source gate, product gate, immutable candidate and portable packaging | Admitted tested candidate |
| `FULL` | Every check above and gate membership inspection | Admitted tested candidate |

The documentation plan admits reviewed pages, navigation, styles, generated views, and their documentation tooling.
It also admits the reader-journey audit reports and Vale profiles from PR #1130.
The frozen path inventory in `fixtures/pr1130-paths.txt` covers all 113 changed files at `d02373e8`.
Tests reject broader validation skips when an installer script, Kotlin source, build file, or workflow joins that inventory.
Before adding a path, inspect its consumers in Gradle, packaging, generators and tests.
README, start, installation and compatibility examples run `packaging/test-installer-entrypoint.py` without Gradle.
When the readability runner or its test exists, the documentation job also runs its pytest, Pyright and Ruff checks.
Most OpenWiki source inputs keep full validation.
Reviewed documentation inputs instead run `check-documentation-inputs` for source paths and links.
That mode emits `DOCUMENTATION_INPUTS` evidence and makes no Kotlin declaration claim.
Full source validation keeps `verifyKnowledgeBase` and its declaration evidence.
A source-inventory test checks the current literal source paths and inline links.
New references to listed pages and unsupported reference forms require review before CI can pass.
Generated callable schemas, product scripts, build configuration, unknown paths and mixed documentation/product changes select full validation.
A reviewed documentation script keeps its direct tests instead of selecting Kotlin product qualification.
Rejected affected plans report their finite failure code, such as `UNKNOWN_OWNER` or `STALE_GRAPH`.
Regular file edits, additions and deletions keep both endpoints of the diff.
Renames are read as deletion plus addition, so an excluded endpoint keeps full validation.
Symlinks, executable modes, type changes and malformed observations select full validation.
Missing history, invalid revisions and unknown events also keep every check.
The source plan accepts main/test Kotlin sources outside the native executable and installation owners.
The product plan covers those release-sensitive owners.
Candidate preflight compares the native owner inventory with the actual native compilation graph.
Changes to build configuration select full validation and must pass that comparison.
Main pushes with source changes select product validation before automatic developer promotion when Mac checks are enabled.
A source-inventory test checks that each listed root remains included in `settings.gradle.kts`.
New modules, fixtures and unknown roots keep full validation until their scope is reviewed.

## Validation records and publication

`required_checks.py` admits only an exact plan whose selected jobs succeeded and whose excluded jobs were skipped.
After that aggregate passes, `validation_outcome.py` repeats source selection and seals `validation.json`.
The record retains the repository, source revision, source tree, comparison base, workflow event and run identities.
CI retains it as `ci-validation-outcome-<source>-<attempt>` for 30 days.


Developer promotion needs that record from the exact successful main push or manual CI run.
The record and producer must agree on the event, source, run ID, and attempt.
Manual CI runs select full validation and need an explicit developer publication dispatch.
A missing, malformed, contradictory or mismatched record rejects promotion.
`SOURCE_VALIDATED` rejects promotion before candidate download.
`PRODUCT_VALIDATED` retains the existing candidate, source-tree and payload admission checks.
`DOCS_VALIDATED` succeeds without a candidate or a promotion record and leaves the developer product pointer unchanged.
It never labels an earlier binary with the documentation revision.
The documentation job runs parser and glossary checks directly; the full product gate also retains those proofs.
That duplication adds unmeasured work to full runs.

## Cache ownership

Gradle cache transport remains owned by the pinned `setup-gradle` action.
Only trusted main runs can write entries.
Pull requests, forks, merge groups and other refs remain read-only.
This prevents PR-scoped writes from consuming space that shared main entries need.
Cleanup runs after successful builds, and existing encrypted configuration-cache handling stays in place.

A successful main candidate reuse skips Gradle setup and retains the existing fast path.
The separate `Populate Gradle caches` workflow supplies cache entries after main pushes and daily at 08:00 UTC.
Its Linux job runs the complete source gate with the fixed validation version `0.0.0`.
Its macOS job runs the three reset-adapter suites when Mac checks and native compilation are enabled.
Both jobs use the same platform, toolchain, cache owner and job identifiers as their CI consumers.
The workflow also accepts a manual dispatch on main.
Other refs skip both cache producers.
The workflow does not compile native executables, create product candidates or publish releases.
It runs independently of required CI checks and developer promotion.
The cache producer adds runner work to populate shared entries even when main reuses a candidate.

Keep setup restore/save reports, build task outcomes, native metrics and job timestamps from necessary runs.
A transport hit does not prove task-output reuse or a speedup.
Candidate versions keep the workflow run number.
Source validation uses the fixed validation version `0.0.0` for product, control, and hosted-plugin resources.
Source identities can still invalidate outputs. Measure their effect.
Source and candidate invocations use `--info` to keep configuration-cache rejection reasons and task input changes.
Source logs stay in the existing source reports; candidate logs stay in the Actions build step.
Keep ordinary source validation at `0.0.0`; do not substitute a run number to improve candidate reuse.
Candidate qualification retains its actual release version and exact source identity.

A local CLI compilation probe reused configuration and left compilation up-to-date on an identical second invocation.
Changing `version` invalidated configuration reuse and changed the included-build JAR output name.
With that version fixed, changing `controlVersion` invalidated configuration reuse and regenerated `ManagementVersion.kt`.
Gradle then identified that generated source as the changed compilation input.
These observations explain version-driven work; they do not measure a CI improvement or explain every hosted cache miss.
No build scan evidence is available for this change.

## Platform checks and native compilation

Mac checks are off by default while the self-hosted Mac is unavailable.
Set the GitHub repository variable `KAST_MACOS_CHECKS` to `true` to enable them.
An absent variable or `false` leaves them off.
The switch covers native compilation, fresh Mac product qualification, the Mac cache job,
both manual release workflows, and automatic developer promotion.
Linux source, portable packaging, documentation, and Linux cache jobs keep their selected checks.
The separate Linux composed-bundle workflow also keeps its existing manual trigger.

Set the variable in [Actions variables](https://github.com/amichne/kast/settings/variables/actions),
or use an account with repository-variable write access:

```sh
gh variable set KAST_MACOS_CHECKS --repo amichne/kast --body false
```

When the Mac runner is available again, opt in:

```sh
gh variable set KAST_MACOS_CHECKS --repo amichne/kast --body true
```

Changing a repository variable affects future runs. It does not remove jobs already queued in an existing run.
Cancel an obsolete run and start a new run after changing the setting.
Workflow changes take effect after the updated workflow is merged.
These switches control GitHub Actions job selection. They do not disable local Gradle checks.

When Mac checks are enabled, CI enables native compilation for main pushes and manual main runs by default.
Pull requests and merge groups keep source, documentation, and portable-packaging checks without native compilation or product qualification.
The existing `KAST_NATIVE_COMPILATION` variable remains a second switch.
Set it to `false` to omit native compilation and its dependent product jobs:

```sh
gh variable set KAST_NATIVE_COMPILATION --repo amichne/kast --body false
```

Source, portable packaging and documentation keep their selected checks.
A full selection becomes `SOURCE_FULL` and runs the complete source gate.
Its validation record says `SOURCE_VALIDATED` and grants no product publication authority.
The source gate cannot reuse a product candidate while native compilation is disabled.
Mac cache jobs, both manual release workflows, and developer promotion also skip while the variable is `false`.
An earlier source-only record still rejects product promotion after the variable returns to `true`.

Set the variable to `true` to restore CI native compilation while Mac checks are enabled:

```sh
gh variable set KAST_NATIVE_COMPILATION --repo amichne/kast --body true
```

An absent native-compilation variable also enables compilation when Mac checks are enabled.
On eligible main runs, other native-compilation values reject the CI selection.

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

## Candidate runner routing

A reused candidate runs the final `Kotlin product` job on `ubuntu-latest`.
That job downloads and retains the qualified payload without executing its macOS binaries.
Reuse admission still requires a successful same-repository pull request and an identical source tree.
The retained record keeps the original platform, toolchain, version, producer and payload digests.

A fresh candidate still needs macOS to exercise the assembled Mac product and seal its identity.
Linux cannot execute those binaries.
Main pushes and manual main runs use the self-hosted `kast-main` runner for native compilation and fresh product qualification.
Both release workflows use that runner and accept only main dispatches.
These jobs never select a GitHub-hosted Mac, even when `KAST_SELF_HOSTED_RUNNER` is absent or false.
With `KAST_MACOS_CHECKS=true`, an unavailable self-hosted Mac leaves these jobs queued.
Leave the switch off to keep validation on Linux until that runner returns.
The reused-candidate Linux route remains unchanged.

The runner service keeps its checkout under its own work directory.
Final product qualification consumes release archives and runs native launcher cases through Gradle against the assembled executable.
Local Python uses the version in `.python-version` through uv and a runner-owned virtual environment.
This avoids the macOS `setup-python` installer's fixed `/Users/runner` prefix.
The native Gradle task passes `-mmacosx-version-min=15.0` to the macOS linker.
Before candidate sealing, it rejects a native binary whose deployment target differs from macOS 15.0.
The local pre-job hook uses `self_hosted_guard.py` before repository steps start.
It accepts only main CI jobs named `native` or `kotlin`, plus manual main jobs named `release` in both release workflows.
It rejects other repositories, refs, events, workflows, and jobs.
Install the hook outside the runner application directory.
Set `ACTIONS_RUNNER_HOOK_JOB_STARTED` in the runner's `.env`.
Restart the service after a hook change.
Keep the installed hook synchronized with its reviewed source.

Reuse admission runs only after a main push.
Manual main runs use fresh candidates.
Verify actual Linux retention with the successful main push job and its candidate artifact after merge.

Run the focused checks from the repository root:

```sh
uv run --locked --script .github/scripts/ci/fast_checks.py
uv run --locked --project docs/tooling pyright --project .github/scripts/ci/pyrightconfig.json
python3 .github/scripts/ci/verify_documentation.py
actionlint .github/workflows/ci.yml .github/workflows/docs.yml .github/workflows/developer-release.yml
```

`fast_checks.py` owns the cheap check sequence. Its default `local` mode runs CI
decisions, candidate rules, release preflight suites, and portable packaging tests.
It accepts an uncommitted tree and stops at the first failed stage.
CI runs its `decisions` mode before scope selection; the separate portable job
retains the Linux container boundary. Run `fast_checks.py pre-push` for manual
qualification. It starts the full product gate after the cheap checks pass.

Use uv 0.12.9 or newer. The script locks its dependencies and selects Python 3.12.
Initial environment provisioning can use the network. Test execution uses private
directories and excludes caller credentials, Git repository variables, user Git
configuration, and pytest filters. The full product gate retains the caller's
project toolchain and GitHub authentication. Cheap success does not establish
source or native qualification, and does not replace the remaining checks above.

The decision and rollout modules have a strict Pyright configuration.
The candidate tests keep product provenance checks and exercise explicit no-publication and rejection paths.
Hosted CI must validate the final pull-request head before review completes.

## Reproduce a candidate build

Supply the committed revision and the candidate version from the CI run:

```sh
bash .github/scripts/ci/reproduce.sh <commit-or-ref> --version <major>.<minor>.<patch>
```

The version has no default. Missing or malformed versions reject the command before worktree creation.

Set `KAST_RELEASE_JDK_25` to a Java 25 home before the command.
The helper resolves the ref once and creates a detached worktree at that commit.
It passes the supplied version to that checkout's `verify.sh` without source changes.
The command uses the pinned IDEA distribution and ignores `KAST_ACCEPTANCE_IDEA_HOME`.

The printed path identifies the kept checkout. Its parent contains `ci.log` with both output streams.
The checkout also keeps the build reports after success or failure.
A failed candidate build preserves its nonzero exit status.

This command runs the complete candidate build and grants no publication authority.

## Local product qualification

`distribution/release/run_product_gate.py` builds one clean checkout with `productBuildGate`.
Local validation uses version `0.0.0`; it does not select a published stable or developer release.
The full commit and tree IDs identify the source, and the record declares its `LOCAL_VALIDATION` role.
The wrapper sets source, Control, and Host versions explicitly and passes the admitted source revision.
It checks clean source before execution and again after Gradle succeeds.
The staged Control build identity must match the admitted version, commit, and tree.
`build/reports/local-product-gate.json` records the outcome; a dry-run cannot claim `VALIDATED`.
A prior success is replaced before each attempt, and Gradle failures keep their exit status.
These before/after checks detect observed drift, not transient edits later restored.
Use an isolated checkout throughout qualification.

This local record grants no publication or installation authority.
Stable release allocation and qualified developer publication keep their own version rules.
Rolling installation selects a qualified developer build through its release track.

The agent experiment checks within `hostObservationTest` separate locked dependency preparation from test execution.
Preparation explicitly includes the development dependencies; pytest then uses that project's interpreter.
Preparation, pytest, and Node contracts each have a 120-second deadline.
Each phase reports its outcome, exit code, and cleanup status before the next phase can start.
A failure retains the last 64 KiB of child output and identifies truncation.
Timeout and cancellation cleanup target only the child process group created for that phase.
Inherited pytest selection options cannot replace required execution with collection.

## Source obligations and qualification

`source_validation.py` admits a clean source tree, runs the offline preflight suites, examines the Gradle graph, and runs `sourceValidationGate`.
A failed offline preflight stops before Gradle starts, including its dry-run.
The failure report lists no graph tasks and keeps every source obligation as missing.
The script records the source tree, task graph, elapsed time, exit code, and dependency compiler count.
Full and affected commands request single-use Gradle daemons with `--no-daemon`.
The local product gate requests the same daemon lifetime.
This keeps a reusable daemon from continuing cancelled work after its client disconnects; disk caches remain enabled.
A changed source tree or failed process cannot produce `SOURCE_VALIDATED`.
This local execution record grants no publication authority.
The separate workflow outcome also binds the exact producer and comparison base.

The graph/configuration phase streams output to the console while Gradle writes directly to `task-graph.log`.
Progress records report elapsed time and time since observed output every 15 seconds.
The default graph deadline is 600 seconds, not an inactivity limit.
`--graph-timeout-seconds <seconds>` accepts integers from 1 through 3600 for a slower cold configuration.
The default is an initial feedback budget, not a measured cold-build percentile.
Preflight, source task execution, product qualification, and native-input preparation keep their existing limits.

An expired graph records `GRAPH_TIMED_OUT` with exit 124; SIGINT or SIGTERM records `GRAPH_CANCELLED` with exit 130 or 143.
Neither outcome starts source execution, even when the partial log lists every required task.
`task-graph-execution.json` retains the process outcome, elapsed time, diagnostic status, and cleanup status.
Before stopping the client, the runner allows two seconds for a bounded process snapshot in `task-graph-process.txt`.
That snapshot contains only client identity, state, elapsed time, CPU time, and RSS, not arguments or environment values.
The client snapshot does not establish the cause of a Gradle stall.

The runner also attempts a bounded daemon thread dump before client cleanup.
It collects daemon PID candidates from streamed Gradle output and accepts only one distinct positive PID.
Process inspection must match this invocation's random marker and the Gradle daemon class.
The runner checks process start identity before and after `JAVA_HOME/bin/jcmd <pid> Thread.print -l`.
Each process inspection has a two-second limit; attachment has a three-second limit and a two-MiB output cap.
Each diagnostic helper's process group is retired, with one second allowed to reap its leader.
Only complete output with confirmed association becomes `task-graph-daemon-threads.txt`.
Raw arguments, environment values, and the invocation marker are not retained in the diagnostic report.
`daemonDiagnostic` records whether evidence was retained, unavailable, truncated, timed out, failed to attach, or lacked confirmed identity.
Diagnostic failure cannot replace the graph outcome or prevent client cleanup.
These association checks do not make attachment atomic with process exit and PID reuse.

Client cleanup signals only the new client process group, with five seconds each for graceful exit and forced-exit observation.
It never runs `gradle --stop` or kills shared daemons by name.
`CLIENT_REAPED` proves client retirement, not synchronous daemon retirement.
`UNCONFIRMED` retains a failed cleanup observation rather than claiming retirement.
Partial graph logs remain available; later attempts remove only the runner's prior reports.

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
Linux owns the source result through `source-work`.
The stable `Source validation` check needs that job, independently of native compilation.
Source-only changes use affected selection with fixed validation versions.
Product changes run the complete source gate before the native input archive is prepared.
A reused candidate keeps its original successful source proof and exact source tree.
Failed, cancelled, missing, and skipped source results reject the source aggregate.
The final required gate also needs the qualified candidate.

The routine candidate path uses four stages:

1. Linux runs source checks and compiles JVM inputs.
2. macOS compiles those inputs with Native Image and examines the executable version and deployment target.
3. Linux admits the native artifact, assembles release archives, examines layout and runtime admission, and generates the SBOM.
4. macOS runs the installed-product tests against those archives and seals the existing candidate record.

Each transfer carries the source revision, source tree, version, artifact kind, and SHA-256 digest.
The native artifact also carries the compiler toolchain digest through assembly.
Final qualification rejects a different toolchain before candidate sealing.
A mismatched identity or digest rejects the next stage before compilation or assembly.
Archive extraction rejects links and paths outside the destination.
The native stage compiles transferred JVM inputs directly with Native Image.
Mac adapter and launcher cases use Gradle.
The native tests still need macOS because Linux cannot execute the Mac binary.
Candidate reuse keeps the existing payload bytes and skips these stages.

Main CI qualifies packaging, installation, native inputs, toolchains, and release policy after merge.
Pull-request source success does not prove native behavior.
Unknown ownership and unavailable history select the full plan.
Repository branch-protection settings remain unchanged.

Local `productBuildGate` and standalone release builds keep the complete gate.
Routine CI distributes its source and native obligations across the target platforms.
Candidate preflight still examines the complete gate and native input inventory.
No matched hosted timing comparison establishes a speedup for this split.

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
All four corpus jobs use `ubuntu-latest`.
Earlier macOS timings do not form a matched cohort with these Linux runs.
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
