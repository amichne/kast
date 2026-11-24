# CI validation and cache policy

## Land a pull request through the merge queue

Add the `queue` label, or comment `@mergifyio queue`, to queue a pull request for `main`.
Comment `@mergifyio dequeue` to remove it.
A pull request needs `Source feedback` to enter the queue.
Mergify puts the exact result for `main` on a draft pull request under `mergify/merge-queue/`, waits for `CI required checks` there, and squash-merges the original.
One pull request is validated at a time.
The GitHub branch rules for `main` must list the Mergify app as an exempt bypass actor, because the original pull request does not carry the required check.
`.mergify.yml` owns the queue rules. GitHub's native merge queue needs an organization-owned repository.

## Run a CI check locally

Run `mise tasks` to list workflows for developers, agents, hooks, and CI.
Use `mise tasks info <task>` to inspect a task and `mise run <task> [arguments]` to run it.
The [mise configuration](../../../mise.toml) pins the tools and includes the task definitions.
The CI task descriptions identify host and artifact requirements. Publication tasks also need the release inputs and credentials of the corresponding job.

| Command | Runs |
| --- | --- |
| `mise run fast` | Cheap checks before Gradle, on an uncommitted tree |
| `mise run module :distribution:cli` | One module's quality guards and unit tests |
| `mise run affected` | The source obligations of this branch's change against `origin/main` |
| `mise run ci:pr` | Decisions, documentation, and complete source obligations, in that order |
| `mise run ci:pr-full` | `ci:pr`, the portable container suites, and the macOS reset adapters |

Brackets in the task list name what a task needs beyond the pinned toolchain.
`[macOS]` and `[Linux]` need that host, `[container]` a running container runtime, and `[network]` public downloads.
`[github]` needs the GitHub API with an authenticated `gh`, and `[artifacts]` needs outputs of earlier CI jobs.
`[clean commit]` needs a clean committed tree.
The task's own command enforces its requirement and fails with a reason.

`workflow_contract.py` enforces this in the decisions suite.
It checks task references in workflows, hooks, and guides. GitHub Actions owns job selection, artifact transfer, and result reporting.

The installed acceptance baseline is the checked-in pin `config/ci/acceptance-baseline.json`.
`acceptance_inputs.py` decodes it, so changing the baseline is a reviewed one-file change.

`validation_scope.py` selects checks from the exact Git diff and the native compilation setting.
The selector checks the checkout revision before it reads the diff.
Pull requests use the merge base. Pushes use the entire before/head range.

| Plan | Required work | Product publication |
| --- | --- | --- |
| `DOCUMENTATION` | Mintlify, parser, glossary, knowledge links, installer examples, and documentation tooling | None |
| `SOURCE` | Affected owners and consumers, their semantic families, shared guards and portable packaging | None |
| `SOURCE_FULL` | Complete source gate, portable packaging and documentation | None |
| `PRODUCT` | Source gate, product gate, immutable candidate and portable packaging | Admitted tested candidate |
| `FULL` | Every check above | Admitted tested candidate |

The documentation plan admits reviewed pages, navigation, styles, generated views, and their documentation tooling.
It also admits the reader-journey audit reports and Vale profiles from PR #1130.
The frozen path inventory in `fixtures/pr1130-paths.txt` covers all 113 changed files at `d02373e8`.
Tests reject broader validation skips when an installer script, Kotlin source, build file, or workflow joins that inventory.
Before adding a path, inspect its consumers in Gradle, packaging, generators and tests.
README, start, installation and compatibility examples run `packaging/tests/test-installer-entrypoint.py` without Gradle.
When the readability runner or its test exists, the documentation job also runs its pytest, Pyright and Ruff checks.
Most OpenWiki source inputs keep full validation.
The documentation job runs `verifyKnowledgeBase` and `verifyKnowledgeClaims` with the isolated Kotlin parser.
Source integrity rejects malformed references and broken document links.
It reports current declaration drift as unresolved evidence without rewriting producer metadata.
The assessment distinguishes integrity acceptance from current evidence and publication qualification.
New or changed explanations still need coherent source attribution.
Full publication qualification needs a complete current candidate and exact input-bound receipts.
Generated callable schemas, product scripts, build configuration, unknown paths and mixed documentation/product changes select full validation.
A reviewed documentation script keeps its direct tests instead of selecting Kotlin product qualification.
Rejected affected plans report their finite failure code, such as `UNKNOWN_OWNER` or `STALE_GRAPH`.
Regular file edits, additions and deletions keep both endpoints of the diff.
Renames are read as deletion plus addition, so an excluded endpoint keeps full validation.
Symlinks, executable modes, type changes and malformed observations select full validation.
Missing history, invalid revisions and unknown events also keep every check.
The source plan accepts main/test Kotlin sources outside the native executable and installation owners.
The product plan covers those release-sensitive owners.
`NATIVE_INPUT_MODULES` lists the native executable owners by hand; no CI step compares it with the native compilation graph.
A pull request run omits the product gate, so `PRODUCT` becomes `SOURCE` and `FULL` becomes `SOURCE_FULL`.
With Mac checks enabled, merge queue runs and main pushes qualify the Mac product for source changes.
A pull request run reports `Pull request checks`. Only a queue run or a main run reports `CI required checks`, which `main` requires.
The product gate cannot run on a branch before the queue: sealing rejects a manual run outside `main`.
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
Only runs on `main` write entries; pull request runs restore them read-only.
Main entries serve every ref, while a pull request entry serves only that pull request.
Each pull request run wrote 4 to 5 GB, so the 10 GB repository quota evicted main's shared entries.
Most runs then started cold and downloaded Gradle, plugins, and dependencies from public registries.
Cleanup runs after successful builds, and existing encrypted configuration-cache handling stays in place.

The Linux source job stores source task outputs.
The macOS native job stores outputs from the reset-adapter suites.
The documentation job stores the plugins and dependencies its knowledge tasks resolve.

### Seed the cache on demand

Agents and maintainers can refresh the shared source cache without a full CI run:

```shell
gh workflow run gradle-cache.yml --ref main
```

The `Seed Gradle cache` workflow runs complete source validation and writes its Gradle home under the `source-work` key family.
Both it and the CI `source-work` job set `GRADLE_BUILD_ACTION_CACHE_KEY_JOB` and `GRADLE_BUILD_ACTION_CACHE_KEY_JOB_INSTANCE`, so the next source run restores the newest seed.
A seed dispatched on a branch writes a branch-scoped entry that pull request runs cannot read.

Keep setup restore/save reports, build task outcomes, native metrics and job timestamps from necessary runs.
A transport hit does not prove task-output reuse or a speedup.
Candidate versions keep the workflow run number.
Source validation uses the fixed validation version `0.0.0` for product, control, and hosted-plugin resources.
Source identities can still invalidate outputs. Measure their effect.
Source and candidate invocations use `--info` to keep configuration-cache rejection reasons and task input changes.
Source logs stay in the existing source reports; candidate logs stay in the Actions build step.
Keep ordinary source validation at `0.0.0`; do not substitute a run number to improve candidate cache hits.
Candidate qualification retains its actual release version and exact source identity.

A local CLI compilation probe reused configuration and left compilation up-to-date on an identical second invocation.
Changing `version` invalidated configuration reuse and changed the included-build JAR output name.
With that version fixed, changing `controlVersion` invalidated configuration reuse and regenerated `ManagementVersion.kt`.
Gradle then identified that generated source as the changed compilation input.
These observations explain version-driven work; they do not measure a CI improvement or explain every hosted cache miss.
No build scan evidence is available for this change.

## Platform checks and native compilation

Mac checks use Apple silicon runners. They are off when the repository variable is absent.
Set the GitHub repository variable `KAST_MACOS_CHECKS` to `true` to enable them.
An absent variable or `false` leaves them off.
The switch covers native compilation, fresh Mac product qualification, both manual release workflows, and automatic developer promotion.
Linux source, portable packaging, and documentation keep their selected checks.
The **Isolated runtime candidates** workflow uses the switch to include macOS targets in its matrix.

Set the variable in [Actions variables](https://github.com/amichne/kast/settings/variables/actions),
or use an account with repository-variable write access:

```sh
gh variable set KAST_MACOS_CHECKS --repo amichne/kast --body false
```

To enable the Mac checks:

```sh
gh variable set KAST_MACOS_CHECKS --repo amichne/kast --body true
```

Changing a repository variable affects future runs. It does not remove jobs already queued in an existing run.
Cancel an obsolete run and start a new run after changing the setting.
Workflow changes take effect after the updated workflow is merged.
These switches control GitHub Actions job selection. They do not disable local Gradle checks.

Mac product checks run in the merge queue, on main pushes, and in manual runs.
Merge queue runs use hosted Mac runners.
A superseded run releases its Mac product jobs.
The existing `KAST_NATIVE_COMPILATION` variable remains a second switch.
Set it to `false` to omit native compilation and its dependent product jobs:

```sh
gh variable set KAST_NATIVE_COMPILATION --repo amichne/kast --body false
```

Source, portable packaging and documentation keep their selected checks.
A full selection becomes `SOURCE_FULL` and runs the complete source gate.
Its validation record says `SOURCE_VALIDATED` and grants no product publication authority.
Both manual release workflows and developer promotion also skip while the variable is `false`.
An earlier source-only record still rejects product promotion after the variable returns to `true`.

Set the variable to `true` to restore CI native compilation while Mac checks are enabled:

```sh
gh variable set KAST_NATIVE_COMPILATION --repo amichne/kast --body true
```

An absent native-compilation variable also enables compilation when Mac checks are enabled.
On eligible main runs, other native-compilation values reject the CI selection.

## Native compiler fixture gate

`check` and `scopedCheck` in `:relation:intellij`, `:symbol:intellij`, and `:runtime:hosted` depend on that module's `nativeFixtureTest`.
Each suite runs every native test class of its module headless in the pinned IntelliJ Platform.
The `Source obligations` job on Linux runs these suites, because it already provisions the native IDE home for the dependency fixtures.
`source_validation.py` requires the three task paths in the complete source graph.

Selection follows the affected-owner plan:

- A plan adds `check` and `scopedCheck` for each affected owner, so an owner's suite runs when that owner or one of its dependencies changes.
- An unaffected owner's suite does not run on that pull request.
- Unknown ownership, a stale graph, or missing history selects the complete gate, which runs all three suites.
- Merge queue runs and main pushes of a product change run the complete gate.

The `KAST_SEMANTIC_FIXTURES` switch does not cover these suites.
They run the semantic case classes too, but only the semantic accountant establishes the exact case population.

On a 2026 Apple silicon laptop with warm compilation, the three suites took 5 minutes 54 seconds of wall time for 783 cases.
Their test time was 319 seconds for 558 relation cases, 126 seconds for 201 hosted cases, and 57 seconds for 24 symbol cases.
The relation suite runs in one fork and bounds the wall time.
In the complete Linux gate of pull request 1376, the relation suite took 15 minutes, the hosted suite 6 minutes, and the symbol suite 2 minutes, with overlap.
That `Source obligations` job took 22 minutes, against 10 minutes before the gate, within its 60-minute limit.

Each suite owns `build/native-test/<task>/` for its IDE configuration, system, and log directories.
The configuration cache can run two suites of one project together, so they must not share those directories.

## Temporary semantic fixture switch

Local runs and CI skip the three `semanticCoverageFixtureTest` suites by default.
Their coverage and inventory report consumers also skip.
`config/ci/semantic-fixture-tasks.txt` owns the six excluded task paths.
Ordinary unit tests, dependency archive fixtures, semantic input checks, and product assembly keep their existing selection.
Skipped semantic fixtures provide no compiler behavior evidence for that run.

Set `KAST_SEMANTIC_FIXTURES=true` to enable the suites and report consumers:

```sh
KAST_SEMANTIC_FIXTURES=true mise run gradle productBuildGate
```

Set the GitHub repository variable `KAST_SEMANTIC_FIXTURES` to `true` to enable them in CI.
Set it to `false` to disable them again.
When CI enables the suites, it builds a fresh candidate instead of reusing an earlier candidate with weaker fixture evidence.
Other values reject the Gradle invocation.
Gradle logs the disabled state, and source execution reports list `skippedTasks` under schema version 2.
The switch does not remove fixture sources or their case manifest.

## Candidate runner routing

Routine pull-request CI checks source without producing a product candidate.
Every main push or manual main run that needs a product builds and qualifies a fresh candidate.
The **Isolated runtime candidates** workflow produces platform archives with its own qualification receipts.

The `Kotlin product` job needs macOS to exercise the assembled Mac product; Linux cannot execute those binaries.
The `native` and `kotlin` jobs use the GitHub-hosted `macos-15` runner by default.
A selector job runs `mac_runner.py` immediately before each of them.
Set `KAST_PREFER_LOCAL_MAC=true` to enable local selection.
With local selection enabled, it picks the Mac only when the GitHub runner API reports an online, idle runner with the `self-hosted`, `macOS`, `ARM64`, and `kast-main` labels.
A missing `KAST_RUNNER_STATUS_TOKEN` secret, a failed request, a malformed response, or a busy or offline Mac selects `macos-15`.
The step summary records the reason.
The secret is a fine-grained token for this repository with read-only **Administration** permission; `GITHUB_TOKEN` cannot list runners.
A Mac that goes offline after selection still leaves that job queued until it returns or the run is cancelled.
Other macOS jobs, including complete-product qualification and installed acceptance, stay on hosted runners because they install products and start Hosts.
The manual component release uses `macos-15`. Stable promotion uses retained bytes.

The `kotlin` and `composed-macos` jobs start after release assembly and run beside complete source validation.
Sealing and installed acceptance also run beside it, because sealing reads only the qualified Mac product inputs.
The join that produces the candidate and the `CI required checks` aggregate still require `source-work` success.
A source failure therefore still blocks the product, and a failed source run can leave a sealed product that no join accepts.
On the hosted 3-core, 7 GB Mac, the native compile took 26 minutes and spent 31% of it in GC.
The same image compiled in about 3 minutes on the 16 GB Linux runner and in under 2 minutes on the local Mac.

### Local runner setup

The runner service is registered as `kast-main-mac` and runs from its own directory.
Its `.env` sets a runner-owned `GRADLE_USER_HOME` so CI never shares the developer Gradle home.
It also sets `ACTIONS_RUNNER_HOOK_JOB_STARTED` to a hook outside the runner application directory.
The hook runs an installed copy of `self_hosted_guard.py` before repository steps start.
The guard accepts only main CI jobs named `native` or `kotlin`, plus manual main jobs named `release` in both release workflows.
It rejects other repositories, refs, events, workflows, and jobs, including fork pull requests that name the runner labels.
Keep the installed guard synchronized with its reviewed source and restart the service after a hook change.
Jobs set up Python through `.github/actions/local-python`, which uses uv with runner-owned tool state.
The runner `.path` must include uv.

## Compile once and join qualification late

Release assembly and Mac qualification run Gradle through `gradle_graph.run` with budgets below their job timeouts.
A stage that exceeds its budget stops with a process snapshot and Gradle daemon thread dumps in `build/reports/native/<stage>/`.
The failure is `GRADLE_TIMED_OUT`, not a silent job cancellation.

Ubuntu assembles the shared release and runs source validation.
The hosted Mac restores the exact assembled release and qualifies the macOS ARM64 runtime.
The sealed record identifies the Mac archives and their digests.
Installed acceptance exercises the shipped Control, coordinator, and Host against those bytes.
Two jobs run its parts in parallel, each on its own runner: `installed-upgrade` upgrades a queried baseline, and `installed-fresh` covers every other case.
The join admits both parts only on one product and one baseline, and writes the receipt that a release reads.
A missing or failed Mac receipt blocks the required check and preview publication.
Ubuntu also assembles and qualifies the Linux x86-64 product. A failure blocks the required check.
The `two-platform-product` job seals one record for both targets and retains it for 30 days. No release reads that record.
Installed acceptance still exercises only the Mac product. Earlier two-platform records still need both receipts.

### Rerun failed product jobs

**Re-run failed jobs** completes a product. GitHub copies each job that it does not run again into the new attempt, and the copy keeps the job's outputs.
Each product job publishes the attempt that produced its artifact, and its consumers download by that attempt, not their own.

- Sealing admits qualification from the same run at its own or an earlier attempt.
- The join admits a record sealed at its own or an earlier attempt.
- The candidate artifact carries its sealed record's attempt.
- Admission observes each producer's run at its final attempt, which must succeed.
  A producer attempt cannot follow that final attempt.
- Promotion selects the validation outcome and the candidate from the latest producing attempt at or before the final attempt.

Run `43453960426` took 2 hours 20 minutes before these changes.
Its hosted Mac spent almost 44 minutes generating Control a second time.
That is measured duplicate work, not a promised wall-time saving.
The changed graph still needs a remote timing measurement.

Final product qualification consumes release archives and runs native launcher cases through Gradle against the assembled executable.
Each hosted job sets up the Python version in `.python-version` and installs the pinned uv version.
The native Gradle task passes `-mmacosx-version-min=15.0` to the macOS linker.
The native stage rejects a binary whose deployment target differs from macOS 15.0.

Run the focused checks from the repository root:

```sh
mise run fast
mise run types
mise run docs:contracts
mise run workflow-lint .github/workflows/ci.yml .github/workflows/docs.yml .github/workflows/developer-release.yml
```

`fast_checks.py` owns the cheap check sequence. Its default `local` mode runs CI
decisions, candidate rules, release preflight suites, and portable packaging tests.
It accepts an uncommitted tree and stops at the first failed stage.
CI runs its `decisions` mode before scope selection; the separate portable job
retains the Linux container boundary. Run `mise run pre-push` for manual
qualification. It starts the full product gate after the cheap checks pass.

Mise provides uv 0.12.9. The script locks its dependencies and selects Python 3.12.
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

`source_validation.py` admits a clean source tree, examines the Gradle graph, and runs `sourceValidationGate`.
The release preflight suites run once, in the scope job's `fast_checks.py decisions` stage.
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
| Relation, symbol, and hosted IntelliJ owners | The complete `nativeFixtureTest` suite |
| CLI | JVM native-adapter tests, query delivery contracts and schemas, callable reference, presentation |
| App Server | Public query generation |
| Installation scripts | Offline lifecycle, recovery, removal, cleanup, system Python, local installation and installer fixtures |
| Included build | Build-logic tests and quality rules |
| Portable container | Onboarding, cloud environment, MCP registration, developer publication, Git attribution, host installation and shared adapter suites |

The graph rejects native compilation, installed-product composition, release runtime admission, hosted-plugin assembly, and release assembly.
Candidate qualification keeps those tasks, actual release versions, acceptance, sealing, and exact identity admission.
Source feedback waits for the selected source, documentation, and portable jobs.
Linux owns the source result through `source-work`.
The stable `Source validation` check needs that job, independently of product assembly.
Source-only changes use affected selection with fixed validation versions.
Product changes run the complete source gate beside release assembly and Mac qualification; sealing and the required aggregate require both.
Failed, cancelled, missing, and skipped source results reject the source aggregate.
The final required gate also needs the qualified candidate.

The routine candidate path uses three stages:

1. Linux runs source checks.
2. Linux cross-compiles the Go Control executable, assembles release archives, examines layout and runtime admission, and generates the SBOM.
3. macOS runs `publicLauncherTest` against the exact assembled Mac Control product.

Each transfer carries the source revision, source tree, version, artifact kind, and SHA-256 digest.
A mismatched identity or digest rejects the next stage before qualification.
Archive extraction rejects links and paths outside the destination.
The Mac stage needs macOS because the assembled Control executable targets it.

Main CI qualifies packaging, installation, toolchains, and release policy after merge.
Pull-request source success does not prove installed product behavior.
Unknown ownership and unavailable history select the full plan.
Repository branch-protection settings remain unchanged.

Local `productBuildGate` and standalone release builds keep the complete gate.
Routine CI distributes its source and product obligations across the target platforms.
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
Explicit shared-resource consumers cover the host-observation suite and the delivery schema read by public-query generation.
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

### Build storage and early prerequisites

Native jobs check free space before setup or compilation.
The Mac compiler checks a 12 GiB floor. Assembly jobs check an 8 GiB floor.
These floors detect low space at job start. They do not reserve disk space.
The isolated Linux job prepares signed support packages before it compiles.
The same prepared files enter assembly.

To examine known generated outputs across linked worktrees, run:

```sh
mise run storage-inventory --repo . --minimum-bytes 1073741824
```

The report gives each output's worktree, allocated size, and activity status.
Activity comes from process working directories that the current user can see.
Treat `not-observed` as a limited snapshot. The command does not remove files.

The native qualification job stages the exact candidate with the public Bash installer.
A local HTTP server supplies the candidate payload and checksums.
The installer runs in a private home. This stage tests the HTTP download and installation path before promotion.
The developer-channel pointer has its own contract tests.
