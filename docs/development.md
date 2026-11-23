# Develop and validate Kast

Use this guide to change Kast itself, select tests, and prepare a reviewable commit.
For a first query in your Kotlin project, use [the first-query tutorial](public/tutorials/first-query.mdx).

Choose the route that matches your change:

- For code, start with [one behavior](#test-one-behavior-at-a-time), then use [the scoped edit loop](#use-the-scoped-edit-loop).
- For prose, use [the documentation policy](internal/documentation-structure.md) and its separate prose and terminology procedures.
- For a local product, read [build and installation](#build-and-install-the-checkout) before changes to your existing installation.
- For release evidence, read [CI candidates and developer publication](#ci-candidates-and-developer-publication).

## Prepare the toolchain

Use Java 25 or newer and the Python version in [`.python-version`](../.python-version).
The project [mise configuration](../mise.toml) pins Temurin 25.0.4 and Go 1.27.4 and sets
`JAVA_HOME` to that JDK. With mise shell activation,
entering this checkout selects the JVM automatically. For scripts and shells
without activation, run commands through mise:

```shell
mise install
mise exec -- ./gradlew :app-server:check
mise exec -- git push
```

The Control executable is a Go binary. `frontend/go.mod` selects its Go release, and
`:buildControlExecutable` cross-compiles it for the `-PkastControlPlatform` target, so
any host assembles any target's Control archive. The installed product runs its JVM
entry on the Host's JetBrains Runtime, not on this build JDK.

Read [AGENTS.md](../AGENTS.md) before making changes. The
[OpenWiki guide](../openwiki/quickstart.md) maps architecture to source and tests.

Use the opt-in [topology impact report](topology-impact.md) to find declared production consumers of changed Kast modules.
Each consumer includes a shortest dependency path for source review and focused tests.

## Test one behavior at a time

Kast's default testing approach is to virtualize the dependencies of one named
behavior and run its real production implementation. A behavior is an externally
meaningful rule or transition; it can span several functions. Start with its minimum
facts, explicit effects and an independently specified expected result. The
[root testing rules](../AGENTS.md#testing) apply to every module; domain-specific
fixtures add only the authority needed for their assertions.

### Choose the execution boundary

Use the smallest boundary that can establish the claim. Declare its dependency
budget next to the fixture, including any reason it needs a wider boundary.

| Claim | Required boundary | Example |
| --- | --- | --- |
| Pure parsing, resolution or policy | Explicit values and production types; no filesystem, child, socket, clock or network | Configuration value, provenance and typed rejection |
| Interpretation of an external observation | Required injected boundary with strict scripted observations | Child exit, I/O failure or deadline mapped to the existing retirement outcome |
| Filesystem or process-adapter semantics | One private root with the necessary files, links, locks or trivial child | Canonical-path admission, before/after identity checks, invocation arguments and environment |
| Compiler or platform semantics | Existing compiler/IntelliJ fixture for the required model and operation | Symbol resolution or a platform-owned project transition |
| Native product composition | Explicitly disposable, authorized environment | Actual IDEA/plugin lifecycle or service registration; routine tests do not establish this |

Build tools, compilation and the test runner are outside the operation's dependency
budget. A filesystem-free Kotlin case still needs a JVM and its declared build
dependencies. Controlled environment variables and PATH limit process inputs;
they do not constitute an OS security sandbox.

### Keep the rule and its evidence real

Use existing contract/service types and owner-local JUnit or unittest fixtures.
Let a double supply file bytes, a child result or another external observation;
let production code decide precedence, ownership, admissibility and failure.
Scripted boundaries must reject undeclared requests, excess calls and unused
expected interactions. A behavior that retries must declare its expected sequence. Check fixture violations separately so a caught exception
cannot make an invalid experiment pass as a product rejection.

Keep each case's state independent. Pass environment/configuration explicitly and
create only the necessary resources under a private root. Use a read callback to
replace an alias between observations; script a timeout result without waiting.
Use a clock or scheduler only when time itself affects the rule. Never borrow a
developer installation, credentials, service domain or IDE profile for answers.

Assert selected values and provenance, closed failures, effect order and protected
state. A rejected operation may legitimately write observation or transition
evidence. An independently specified oracle must distinguish those allowed effects
from destructive changes. Scripted success does not prove native integration or
authorize cleanup that still needs ownership receipts.

Retain already-small tests. When a coupled case needs revision, split its starting
states and move outcome-only assertions to the lower boundary. Keep adapter and
composition checks that establish distinct facts. Reuse runners and local helpers;
a dependency seam does not require a new framework, module, command or production
test flag. Unrelated test cleanup belongs in a separate change.

### Run focused checks, then widen by ownership

From the repository root, examples using the existing runners are:

```shell
./gradlew :distribution:contract:test \
  --tests 'io.github.amichne.kast.distribution.contract.configuration.KastConfigurationResolutionTest'
./gradlew :app-server:test \
  --tests 'io.github.amichne.kast.appserver.SavedConfigurationIngressTest'
python3 packaging/internal/verification/run-portable-tests.py packaging/tests/test-installation-recovery.py
mise exec -- uv run --locked --project packaging python packaging/tests/test-installer-entrypoint.py InstallerEntrypointTest.test_obsolete_component_options_reject_before_effects
```

These selectors require no product assembly, release download or native acceptance.
Repeat a unittest selector to inspect two independent instances, or step through a
case with the existing debugger. Widen to affected test files and direct consumers,
then run the repository-required guards for changed contracts, architecture and
documentation. Full build/release gates remain separate evidence; these focused
checks do not replace them.

### Gradle configuration and Build Scans

The build enables Isolated Projects, the configuration cache, and the local build cache.
Each module publishes its own architecture facts and generated outputs.
Root tasks consume those artifacts without access to another project's mutable Gradle model.
An ordinary `help` command does not collect module facts or compile module sources.

Use `--no-isolated-projects` to compare behavior during a Gradle plugin investigation.
Keep the task selection and cache state identical for timing comparisons.

Build Scan publication is opt-in.
After you accept the public service terms, request a scan with:

```shell
mise exec -- ./gradlew help --scan -PkastBuildScanTerms=yes
```

The public service receives build metadata, dependency details, and console output.
The settings redact the username, host names, and IP addresses.
The scan records the source revision and supplied GitHub run identifiers.
An uncommitted edit still needs a separate diff record for exact source identity.

For build-logic edits, select the needed test class:

```shell
mise exec -- ./gradlew -p build-logic test --tests '*VerificationGraphTaskTest'
```

The `test` task excludes the separate JSON parser suite.
The build-logic `check` task still includes both suites.

### Use the scoped edit loop

Run the module's `scopedCheck` task with `--continue` to collect independent failures:

```shell
mise exec -- ./gradlew :distribution:cli:scopedCheck --continue
```

The task includes formatting, both file-length guards, `Detekt`, and the module's unit tests.
It does not assemble the product or compile the native command.
Use fully qualified task paths to select modules.
Repeat the same command after each source change.

For a change to prose, include the glossary task and select files for Vale:

```shell
mise exec -- ./gradlew :distribution:cli:scopedCheck \
  verifyDocumentationGlossary verifyScopedProse \
  -PkastProsePaths=docs/development.md,docs/internal/tooling/README.md --continue
```

`kastProsePaths` accepts distinct repository-relative `.md`, `.mdx`, and `.txt` paths separated by commas.
Absolute paths, parent traversal, empty entries, and duplicate entries reject before Vale starts.
The pinned prose assessment remains a separate necessary step for changed prose.

The glossary task examines current prose and generated views without the tooling tests.
The full `check` and `productBuildGate` tasks still include `documentationToolingTest`.
Reports for deterministic script tasks reside in `build/reports/validation/`.
Unchanged inputs reuse their reports through Gradle task history.
Source, policy, tool, environment, and report changes rerun their consumers.
Failed commands preserve their diagnostics and remain failures on later invocations.

The scoped loop supplies local evidence for selected owners.
Before a push, the optional pre-commit hooks run affected source obligations on the committed source.
Live installation and release-catalog reads keep their existing freshness rules.

### Run one suite or one generator check

Some packaging suites import modules that only their runner puts on the module path.
Run one or more of those suites through the portable runner, which supplies the module path and an owned home:

```shell
mise exec -- uv run --locked --project packaging python \
  packaging/internal/verification/run-portable-tests.py packaging/tests/test-managed-bootstrap.py
```

Each argument must name a `packaging/tests/test-*.py` suite; the runner rejects any other path.
A suite that starts without its module path exits with a message that names this command.
`test-install-checkout.py`, `test-managed-bootstrap.py`, and `test-managed-build-record.py` carry this guard.

Each generator has a `--check` mode that rejects stale output without changing files:

```shell
python3 packaging/internal/bootstrap/generate_bootstrap.py --check
python3 packaging/internal/query/generate-public-query.py --check
```

The first command compares the root `install.sh` with the bootstrap template.
The second command compares the public query projections with the authored tool schema.

`source_validation.py` needs a clean committed tree, so it cannot check work before a commit.
Before a commit, run `fast_checks.py` and the `scopedCheck` task of each changed module.
Add the single-suite and generator checks above for the files that you changed.

Run all offline packaging suites in an isolated Linux container with:

```shell
./packaging/internal/verification/run-portable-tests-container.sh
```

The runner mounts the checkout read-only, disables network access, and gives each
test process an owned home, executable temp directory for fixture scripts, and
tool path. Installer policy tests supply a private `uname` observation for the
supported macOS case and separately assert rejection of Linux. It includes every
remaining `packaging/tests/test-*.py` suite. Platform-specific installer cases remain
gated to macOS; the Linux container does not qualify them. The runner rejects
unexpected skips and reports the expected platform skip count (currently 15 on
Linux, zero on macOS arm64).
CI runs this container suite on a disposable Ubuntu runner for every pull request
and main push, alongside the macOS product checks.

Run the same offline inventory without Docker using
`python3 packaging/internal/verification/run-portable-tests.py`; add suite paths to [run only those suites](#run-one-suite-or-one-generator-check). A container pass proves the offline installer contract
and process tests on Linux. It does not establish native IDEA or Codex behavior.

Report the command, observed result and evidence level: pure policy, private
filesystem/process adapter, compiler/platform fixture or native composition. Name
skips and anything not verified. The [configuration and retirement verification
record](reviews/behavior-sized-testing.md) contains a concrete migration inventory,
complete selectors, resource reductions and observed limits.

## Investigate bounded impact reads

Retained result pages construct at most the admitted result grant, capped at 100
items per page. Follow the returned cursor and retain the original impact closure;
finishing a presentation does not close unresolved semantic obligations.

The default query checkpoint is 32 MiB, within a 128 MiB total continuation
retention budget. The total includes independently owned checkpoints, retained
results and fitted output pages. Already conservative checkpoint byte charges
are added once to each owner that retains them. The conservative storage accounting includes
physically retained proof objects and references. Equal callable proofs can share
storage only when their authority, scope, constraints and compiler evidence all
match; independent paths, invocations and observations remain distinct.

If a larger investigation reaches checkpoint capacity, inspect its typed stop and
qualified findings. The configuration catalogue exposes
`KAST_READ_QUERY_CHECKPOINT_BYTES` and its IDE property
`kast.read.query.checkpoint.bytes`. A configured checkpoint must fit the total
continuation retention grant. Increasing a result-page grant does not enlarge it.

## Run cheap checks before building

Run the local feedback loop while editing, including on an uncommitted tree:

```shell
uv run --locked --script .github/scripts/ci/fast_checks.py
```

Use uv 0.12.9 or newer. The script selects Python 3.12 and locked test dependencies.
The first invocation can download that environment. After provisioning it, add
`--offline` to `uv run` to reject dependency downloads.

The command runs CI decisions, candidate and release rules, and the portable
packaging inventory. Each stage reports its outcome and elapsed time. A failed
stage stops later stages. The tests use private home and temporary directories
without caller credentials, Git repository variables, or user test filters.
This command starts no Gradle build, native compilation, or release-catalog query.
It does not require a clean commit or authenticate to GitHub.

Passing these checks does not qualify Kotlin source, a Linux container, native
binaries, or a release. Continue with the focused module checks for changed code.
The [CI policy](../.github/scripts/ci/README.md#ci-validation-and-cache-policy)
describes the separate source and product gates.

## Build and install the checkout

Gradle provisions the pinned Python dependencies for its schema tests in
`build/python-tests/env` with `uv sync --locked` from the uv project in `config/python`.
The build does not depend on the active shell's Python packages or the interpreter search
path retained by an existing Gradle daemon.

The retired Kast push dispatcher still exits without local qualification.
The repository instead declares opt-in [pre-commit](https://pre-commit.com) hooks in
`.pre-commit-config.yaml`:

```shell
uvx pre-commit@4.7.0 install --hook-type pre-commit --hook-type pre-push
```

A commit that touches CI or release files runs the CI decision suites.
A push runs affected source obligations against `origin/main` with the local Gradle caches.
When the push changes documentation, it also runs the documentation contract checks from the CI documentation job.
The source hook needs a clean committed tree, like the source gate.
Use `git push --no-verify` to skip the hooks for one push.

Run complete local qualification explicitly.

```shell
mise exec -- uv run --locked --script .github/scripts/ci/fast_checks.py pre-push
```

The `pre-push` mode starts the product gate after the cheap checks pass.
The product gate needs a clean committed checkout and uses version `0.0.0`.
Its receipt identifies the source revision and tree. Release candidates use
their own explicit version and qualification.

### Disposable local installed acceptance

On macOS ARM64, use `packaging/internal/verification/local_product_probe.py` to exercise the installed
product without a published two-target release candidate. This contributor path
does not change release admission or grant release qualification.

Build a positive candidate version such as `0.0.1`. Assemble its matching Control
and Host with `packaging/internal/composition/composed_release.py assemble --full-native-archives`.
Keep the other required assembly arguments. This option retains a full runtime
archive instead of splitting a shared base; the default release path is unchanged.
Run `packaging/internal/composition/composed_release.py qualify` with those exact archives.
Qualification verifies archive identity and passive Control discovery; it does not start a compiler Host.
Installed acceptance exercises compiler queries through the shipped Control, without a separate native test binary.

Supply the resulting component receipt, pinned interpreter inputs, and an explicit
macOS sandbox profile to the local probe. Use a fresh evidence directory.

```shell
mise exec -- uv run --locked --project packaging python packaging/internal/verification/local_product_probe.py \
  --bundle "$BUNDLE" --runtime "$RUNTIME" --qualification "$QUALIFICATION" \
  --private-python-archive "$PRIVATE_PYTHON_ARCHIVE" \
  --private-dependency "$PRIVATE_DEPENDENCY" --sandbox "$SANDBOX" \
  --trials 4 --evidence "$EVIDENCE"
```

Each trial installs into an owned temporary HOME, releases three cold queries
concurrently, and checks a shared compiler lifetime with a warm query.
Uninstall separates the trials. The final trial damages an owned runtime file
while its Host is live. Repair uses the packaged coordinator and real retirement
child, then checks restored bytes and a fresh Host. The probe checks protected
files before final removal.

Local acquisition uses a schema fixture with no Linux payload or real CI producer.
Its report explicitly records `releaseQualification=NOT_ESTABLISHED`. It does not
prove public network acquisition, `kast upgrade --repair` selection, a different-build
upgrade, Linux behavior, or complete product qualification. Failure retains the
disposable root named in `disposable-root.txt`; inspect it before cleanup.

### Retire leaked harness launchd jobs

A harness that overrides HOME still submits its semantic coordinator to your own
`gui/<uid>` launchd domain. The local product probe and the installed-product
lifecycle probe retire every `io.github.amichne.kast.semantic.*` job under their
disposable root, after a failure too. Then they check that no such job remains.
A job that leaked before this check, or from a one-off experiment HOME, keeps a
headless Host resident. To list such jobs, run:

```shell
python3 packaging/internal/verification/harness_launchd.py
```

Add `--retire` to boot out the listed jobs. The command selects a job only when
its job definition, executable and working directory all lie under one recognized
harness root:

- `~/.cache/kast-exp-home*`
- `~/.local/share/kast-local/<name>`
- `kast-installed-native-*` and `kast-local-installed-*` in the temporary directory
- `/private/tmp/kast-local-install-*` and `/private/tmp/kn -*`

It never selects the real installation under `~/.local/share/kast`. A kept
`kast-local` product starts a new coordinator on its next query.

### Install a checkout as an isolated managed product

On macOS ARM64, `packaging/internal/product/local_product_install.py` installs one clean committed
checkout as a managed product you can keep using. Use it to run a branch with
private workspace containers before release. It never replaces the user installation.

```shell
mise exec -- uv run --locked --project packaging python packaging/internal/product/local_product_install.py \
  install --root ~/.local/share/kast-local/my-branch --source /path/to/clean/checkout
cd /path/to/workspace && ~/.local/share/kast-local/my-branch/bin/kast query --name MyClass --json
```

The first semantic request in a workspace creates its private container automatically.

The command admits the source with the release source check and rejects staged,
uncommitted and untracked changes. It builds the checkout's own CI release tasks
under a fresh `0.0.N` version and assembles full native archives. Component assembly
qualification runs in a macOS sandbox that denies access to your home directory.
The command admits the source again, then installs through the production
activation. Only release acquisition reads local files, as in the disposable probe.
Pinned donor, interpreter and dependency inputs are cached by digest under
`~/.cache/kast-local-product`.

The root holds an isolated HOME, the qualification receipt, and a `local-product.json`
receipt with the exact source and archive digests. Run `bin/kast` from the root to use
the product. It binds HOME, XDG directories and `KAST_INSTALL_ROOT` to the root. Other
variables pass through, including `GRADLE_USER_HOME` and `KAST_*` settings. Because
HOME changes, the product does not read your global Git or Gradle configuration
unless you set them explicitly.

Remove the root with `local_product_install.py remove --root ROOT`. It stops every
Host, uninstalls, and retires any semantic coordinator launchd job whose files lie under the root.
It checks that no such job and no process remains below the isolated HOME, then deletes the root.
A failed stop or uninstall still retires those jobs and keeps the root. A failed install keeps its work directory under `/private/tmp`
and reports the failed stage and its log. The receipt records
`releaseQualification=NOT_ESTABLISHED`.

## CI candidates and developer publication

CI selects its plan from the exact diff.
Reviewed documentation paths can use the `DOCUMENTATION` plan without a product candidate.
Installer examples, new paths, and unclassified changes keep full validation.
The [CI policy](../.github/scripts/ci/README.md#ci-validation-and-cache-policy) owns the complete plan table and selection rules.

The `PRODUCT` and `FULL` plans build and qualify a complete Control/Host pair.
CI fixes the candidate version to `0.0.<CI run number>` before compilation.
Retries retain that version and record the producer attempt separately.
The identity record retains the full run ID; the version uses an installer-compatible integer.
The candidate contains the installable payload and a typed identity record.
The record retains the build commit, Git tree, toolchain, platform, and every file digest.
CI retains candidates for 30 days.

Main CI builds a fresh candidate for every product run; it does not reuse pull-request payloads.

Successful main CI from a push triggers the developer publication workflow automatically.
A `DOCS_VALIDATED` result leaves the developer product pointer unchanged and publishes no candidate.
Product publication needs the exact run's `PRODUCT_VALIDATED` record.
It downloads the retained candidate and runs no Gradle build.
A separate promotion record binds the build commit to the merged commit and successful main run.
The immutable developer release contains both records.
If main advances before publication, the publisher retains the existing latest pointer.
`kast versions` lists qualified main builds from the published catalog.
`kast upgrade --channel preview` selects the latest qualified development build.

If current main has no qualified product candidate, start full CI manually:

```shell
gh workflow run ci.yml --ref main -f rollout=none -f rollout_case=all
```

Wait for that exact run to succeed with `PRODUCT_VALIDATED`.
Manual CI does not trigger developer publication automatically.

To publish that candidate or retry publication for current main, supply its successful CI run ID:

```shell
gh workflow run developer-release.yml --ref main -f run_id=<successful-main-CI-run-ID>
```

The publisher verifies the source and producer again.
An existing release must retain the same source and exact file digests.
A failed producer or expired artifact cannot authorize publication.
Older candidates without the identity record require a fresh main build.

Stable full and component releases retain their explicit semantic versions.
Those versions currently change compiled Kotlin, the native executable, and plugin metadata.
A different release version therefore requires a separate build.
Developer promotion never renames or edits a tested payload.

### Build evidence and dependency trust

CI pins Temurin 25.0.4 and retains Gradle task outputs.
The repository secret `GRADLE_ENCRYPTION_KEY` enables encrypted configuration-cache storage.
Fork workflows do not receive the key.
The process adapter records success, process failure, or execution refusal in `build/reports/ci/build-execution.json`.
Gradle profiles remain in `build/reports/profile`.
These reports support comparisons on matched source, toolchain, options, and runner capacity.

```shell
./gradlew build
./gradlew assembleRelease
```

Dependency verification uses the checked `gradle/verification-metadata.xml` in strict mode.
IDE source and Javadoc attachments are trusted by artifact name so IDEA can import and
index dependencies without requiring a checksum update for each optional attachment.
This includes the Gradle distribution source archive. Compiled artifacts and dependency
metadata still require checked SHA-256 values. Source attachments are not checked for
tampering; do not treat them as evidence for release provenance. For an intentional
dependency update, review newly generated checksums against the publisher before
committing the metadata; keep verification enabled for builds and IDE sync.
A successful CLI build does not establish that IDEA imported the Gradle model.

### Installer policy

Keep the public installation path simple: select the latest qualified development build and replace Kast-owned assets.
Kast owns all directories for its installed assets, including Control, Host, and bundled agent guidance.
Replace stale contents within those directories.
End active Kast sessions when necessary to complete replacement.
Keep source workspaces, the IDEA application, and unrelated agent assets outside that ownership.

Artifact identity, checksums, contracts, and finite failure outcomes remain necessary.
A rollback or recovery result does not complete an update to the latest build.
After recovery succeeds, resolve the latest build again and complete its installation and Host activation.
The [installation reference](public/reference/installation.mdx) owns remote installation commands and current restart behavior.

### Install a local build

Use [Install a checkout as an isolated managed product](#install-a-checkout-as-an-isolated-managed-product) for contributor builds.
That tool admits a clean committed checkout, assembles and qualifies its product, then uses the production activation owner.
It does not replace the user's installation. The desktop checkout scripts and Gradle `installLocal` task are retired.

Producer tooling retains independent component release inputs and package tasks:

```shell
./gradlew assembleControlRelease -PcontrolVersion=0.50.0
./gradlew assembleHostRelease -PhostedPluginVersion=0.49.0
```

A control release requires the canonical hosted contract and no host archive.
A host release packages its own runtime dependencies. `assembleRelease` remains
one fresh-install convenience for a selected pair. Changing a control version
does not change the host version or hosted contract.

Persistent installation stops the previous installed App Server and enables
the new login service. This requires the App Server’s
[Codex prerequisites](../app-server/docs/compatibility.md). Service enablement
failure leaves the installation available and reports failure.

## Validate documentation

Kast uses one Mintlify site with four reader tracks: Tutorials, How-to guides, Reference, and Explanation. The README presents product value and the default installation path.

From `docs/public`, run:

```shell
npx mint@4.2.841 validate
npx mint@4.2.841 broken-links
npx mint@4.2.841 dev --port 3000
```

Open `http://localhost:3000` and examine both tabs, their navigation, and their links. Edit the MDX pages and `docs.json` together.

The callable generator writes `docs/public/reference/callables.openapi.json`. The protocol registry keeps ownership of the generated contract. Use the [documentation structure](internal/documentation-structure.md) for route and content rules.

After changing source-bound knowledge, run from the repository root:

```shell
./gradlew knowledgeImpact verifyKnowledgeBase verifyKnowledgeClaims
```

For an interrupted installation, rerun the installer: it resumes the recorded replacement journal or reports `RECOVERY_REQUIRED`.

### Installer development inputs

`packaging/internal/bootstrap/generate_bootstrap.py` owns the generated root `install.sh` and retained installer support membership.
Gradle's `generateInstallerSupport` projects that support and produces target-specific MCP dependencies before packaging.
The producer uses uv with locked hashes and binary wheels. Installed setup copies admitted dependency files; it never runs a package manager.

The bootstrap downloads pinned private Python and XML dependency bytes. Self-contained setup is not offline setup.
The retained `install-candidate.sh` adapter uses only that private interpreter for upgrade, recorded recovery, and product removal.
Missing or changed support rejects before lifecycle execution. Historical installations keep their recorded compatibility contracts.

Product fixtures use a bounded local release source with production parsing and activation.
Contributor tooling can require Python and uv; installed lifecycle commands do not.
Restricted-PATH fixtures prove only their exercised process contracts, not native downloads, Host readiness, or release qualification.

`installedProductTest` consumes complete candidate and baseline directories, not a staged Control/Host pair.
The baseline must have a different qualified composition. The task does not assemble replacement artifacts.
Supply pinned interpreter inputs, the native target, and new evidence and receipt paths:

```shell
mise exec -- ./gradlew installedProductTest \
  -PkastAcceptanceCandidate="$CANDIDATE" -PkastAcceptanceBaseline="$BASELINE" \
  -PkastAcceptancePythonArchive="$PRIVATE_PYTHON_ARCHIVE" \
  -PkastAcceptanceDependency="$PRIVATE_DEPENDENCY" -PkastAcceptanceTarget="$TARGET" \
  -PkastAcceptanceEvidence="$EVIDENCE" -PkastAcceptanceReceipt="$RECEIPT"
```

The evidence directory keeps `host-logs/`, which holds the last 512 KiB of each Host `idea.log` and `launcher.log`, at most 16 files.
It also keeps `stage-timings.json`, which records the elapsed milliseconds and exit code of each installation and command, up to 256 stages.
A stage that raised records no exit code.
Each command's `<case>.stderr-timeline.json` lists when each line of `<case>.stderr` arrived, in milliseconds from the stage's start, for up to 4096 lines.
The product's lifecycle events carry no timestamps, so these offsets show which event a slow command waited on.
The probe captures them before uninstall removes the logs on success and after the failure stop otherwise.

Version-pinned archives must match the installer contract. To stage historical
archives that require retired setup inputs, use their matching tagged installer.
The adjacent-patch acceptance helper supports archives with the current contract.

## Workspace readiness proof boundary

The [workspace recovery proof](reviews/workspace-readiness-recovery-989.md)
records ownership decisions, policy properties, production mutations and native
validation limits for issue #989.

`WorkspaceCapabilityReadiness` reports detached facts for `MODEL_PREPARATION`.
The IntelliJ query adapter validates the selected open, initialized project,
exact canonical root, complete current cached Gradle model, smart indexing,
K2 mode, and compatible running host. It then observes the existing retained
project epoch source. A ready observation carries the root, endpoint incarnation,
and opaque epoch. Missing or conflicting evidence produces an unavailable,
pending, or blocked outcome with a finite reason and supported next action.

This preparation observation grants no semantic execution authority. Semantic
queries still capture and validate the operation's source ownership, module,
dependency, compiler, content, PSI, and index requirements through their existing
admission boundaries. `validateWorkspaceEpoch` is the pure same-source final
freshness rule consumed by passive VFS admission. It rejects moved epochs,
different incarnations, and unavailable observations before a capability can be
issued. Retaining a ready snapshot or receiving a successful refresh callback
does not satisfy a later freshness check.

The query lifetime also exposes detached native execution settlement. Cancellation
and caller deadlines preserve each invocation's permit and diagnostic identity
through cleanup. Retirement denies new admission and late publication while
retaining outstanding invocation observations until their native work terminates.
Preparation reports pending native work with the retained model observation and
active operation identities. A retired endpoint incarnation remains blocked and
requires a newly attached host; it does not imply the live project was disposed.

The query owner retains its last successful preparation observation for the same
native project identity. If refresh or observation cancellation makes current
model checks unavailable, inspection still shows the earlier observed model and
the current rejection separately. History never substitutes for a new ready
observation or a final semantic freshness check.

The associated IntelliJ modules own the truthfulness of these observations and
native settlement guarantees. Pure tests establish the decisions conditional on
those guarantees; synthetic observations do not qualify IntelliJ behavior.
`WorkspaceEpochValidationTest` enumerates bounded sequences of model movement,
disposal, reopening, readmission, and irrelevant metadata changes. Its independent
oracle tracks fixture state and rejects deliberate stale-snapshot, retirement,
and incarnation-bypass mutations. `HostedWorkspaceReadinessTest` exercises the
production adapter projection with detached epoch sources and no native project,
filesystem, Git, import, or registration fixture.

## Compare complete engineering tasks

Use this procedure for a controlled budget comparison, after the ordinary [engineering task](public/how-to/complete-engineering-task.mdx) succeeds.
It defines a comparison, without a claim of measured time, heap use, or a completed native trial.

Keep source revisions, requests, fixtures, and host conditions fixed.
Restore only the disposable fixture between runs.
Keep response limits equal across profiles.

Record each measure for the whole task:

| Measure | Record |
| --- | --- |
| Share of tasks that complete | Tasks with the expected answer and passing project tests, divided by all trials. |
| Whole-task time | Time from discovery through the final test, with retries and recovery. |
| Extra steps | Extra calls, manual budget changes, and unresolved decisions. |
| Response size | Actual encoded bytes across all calls and pages. |
| Memory | Host heap use and charged checkpoint bytes as distinct measures. |
| Editor response | Editor responsiveness and cancellation settlement during the task. |
| Coverage and current state | Original completion verdicts, identity evidence, and stale-state rejection outcomes. |

Include controls for a smaller work grant, a stale reference, and caller cancellation.
Keep a typed rejection distinct from a completed task.
Do not raise retention quotas without memory evidence.

## OpenWiki upkeep

The repository's architecture concepts live in `openwiki/`. Use the OpenWiki
Codex skill to search and read relevant sections, or follow the quickstart map
when retrieval is unavailable. Recheck important details against source.

Use `bin/kast-knowledge-refresh` for native maintenance in an isolated checkout.
The coordinator preserves OpenWiki's begin/plan/next-page/submit-page/finish lifecycle and interrupted checkpoints.
OpenWiki owns Claims, source versions, category indexes, provenance, and run state.
Ordinary source work updates authored explanations when behavior changes.
It does not reconcile the complete Claim corpus or rewrite unrelated producer metadata.

`knowledgeImpact` reports affected concepts.
`verifyKnowledgeBase` and `verifyKnowledgeClaims` reject malformed records and report current evidence separately.
Review age requests maintenance. It does not prove falsehood or a fresh review.
Declaration presence proves syntax, not compiler resolution or native execution.
Full publication qualification needs a completed current bundle and input-bound prose and rendered receipts.
An incomplete maintenance run keeps the accepted public snapshot unchanged.

`verifyKnowledgeBase` rejects a `code_sources` path that no longer exists.
A change that moves or removes a cited source must update or retire that citation in the same change.
`code_sources` is repository-owned metadata, so that change can correct it directly.
Two route findings stay advisory: a missing `sources` resource, and a link to a cited or previously recorded source file that no longer exists.
OpenWiki derives `sources` from Claim evidence, and generated pages are not edited by hand.
Those findings therefore appear in the assessment and request a native refresh.
`verifyPublicKnowledge` rejects any page under `docs/public` that links to a repository path that the main branch does not serve.
A `blob` link must name a tracked file, and a `tree` link must name a directory of tracked files.
The check reads the pages as they will be published, so a projected explanation cannot introduce a broken link.

The GitHub workflow uses manual dispatch and one maintenance concurrency group.
It needs its own API credential and selects its configured native model.
`--readiness` reports credential presence without secret values. It does not prove authentication or model access.
A daily cadence remains a proposal until authenticated readiness succeeds and the user authorizes the schedule.
No subscription token belongs in repository secrets or checked-in files.
