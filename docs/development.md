# Develop and validate Kast

Use Java 25 or newer and the Python version in [`.python-version`](../.python-version).
The project [mise configuration](../mise.toml) pins GraalVM 25.0.2 and sets
`JAVA_HOME` and `GRAALVM_HOME` to that installation. With mise shell activation,
entering this checkout selects the JVM automatically. For scripts and shells
without activation, run commands through mise:

```shell
mise install
mise exec -- ./gradlew :app-server:check
mise exec -- git push
```

Native assembly requires a Java 25 toolchain with GraalVM Native Image. Gradle
selects that toolchain independently of the shell's Java and provisions it through
the configured Foojay resolver when no matching local installation is detected.
Toolchain provisioning requires network access on its first use. For an existing
local GraalVM that Gradle does not detect, pass its JDK home with
`-Porg.gradle.java.installations.paths=/absolute/path/to/graalvm`.
`GRAALVM_HOME` is also registered as a toolchain discovery input. If an older
local GraalVM rejects the reachability metadata schema, update that installation
to a current Java 25 patch or supply the newer JDK's path through these inputs.

Read [AGENTS.md](../AGENTS.md) before making changes. The
[OpenWiki guide](../openwiki/quickstart.md) maps architecture to source and tests.

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
python3 packaging/test-installation-lifecycle.py RetirementBoundaryTest
python3 packaging/test-installation-lifecycle.py RetirementAdapterTest
python3 packaging/test-installer-entrypoint.py InstallerEntrypointTest.test_noninteractive_collision_fails_closed_at_selected_command_directory
```

These selectors require no product assembly, release download or native acceptance.
Repeat a unittest selector to inspect two independent instances, or step through a
case with the existing debugger. Widen to affected test files and direct consumers,
then run the repository-required guards for changed contracts, architecture and
documentation. Full build/release gates remain separate evidence; these focused
checks do not replace them.

Run all offline packaging suites in an isolated Linux container with:

```shell
./packaging/run-portable-tests-container.sh
```

The runner mounts the checkout read-only, disables network access, and gives each
test process an owned home, executable temp directory for fixture scripts, and
tool path. Installer policy tests supply a private `uname` observation for the
supported macOS case and separately assert rejection of Linux. It includes every
remaining `packaging/test-*.py` suite. Platform-specific installer cases remain
gated to macOS; the Linux container does not qualify them. The runner rejects
unexpected skips and reports the expected platform skip count (currently 15 on
Linux, zero on macOS arm64).
CI runs this container suite on a disposable Ubuntu runner for every pull request
and main push, alongside the macOS product checks.
Run the same offline inventory without Docker using
`python3 packaging/run-portable-tests.py`. A container pass proves the offline installer contract
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

## Build and install the checkout

Gradle provisions the pinned Python dependencies for its schema tests in
`build/python-tests/env`. The build does not depend on the active shell's Python
packages or the interpreter search path retained by an existing Gradle daemon.

Install the repository's pre-push gate once per Git clone. It preserves other
hooks and runs `productBuildGate` on a clean checked-out commit before a push.
Pre-push fetches the published GitHub release catalog on every invocation.
It builds the checkout with the highest stable semantic version.
It requires authenticated `gh` access and fails if the catalog is unavailable or has no published stable version. Local tags and a previous gate run cannot supply a fallback:

```shell
./.githooks/install.sh
# Run the same gate manually, refreshing the published version first:
python3 distribution/release/run_product_gate.py
```

The wrapper logs the selected release version before starting Gradle. It builds
the current checkout with that version; it does not download or validate released
binaries. Exact release-candidate builds continue to pass their intended version
explicitly.

## CI candidates and developer publication

Every PR builds and tests one complete Control/Host pair, including documentation-only PRs.
CI fixes the candidate version to `0.0.<CI run number>` before compilation.
Retries retain that version and record the producer attempt separately.
The identity record retains the full run ID; the version uses an installer-compatible integer.
The candidate contains the installable payload and a typed identity record.
The record retains the build commit, Git tree, toolchain, platform, and every file digest.
CI retains candidates for 30 days.

After merge, main CI searches successful runs for the merged PR's exact head.
It reuses a same-repository candidate only when its complete Git tree matches merged `main`.
The candidate retains its original commit and version.
Missing or expired candidates, changed trees, and fork producers require a fresh main build.
Malformed records, damaged payloads, and incomplete observations reject reuse.

Successful main CI triggers developer publication automatically.
Publication downloads the retained candidate and runs no Gradle or native-image build.
A separate promotion record binds the build commit to the merged commit and successful main run.
The immutable developer release contains both records.
If main advances before publication, the publisher retains the existing latest pointer.
The public installer's `--developer-latest` flag selects the build named by that pointer.

To retry publication for current main, supply its successful CI run ID:

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

CI pins GraalVM 25.0.2 and retains Gradle task outputs plus the native reachability-metadata repository.
The repository secret `GRADLE_ENCRYPTION_KEY` enables encrypted configuration-cache storage.
Fork workflows do not receive the key.
Native-image builds retain structured analysis and resource metrics in `build/reports/native/build-output.json`.
The process adapter records success, process failure, execution refusal, or missing metrics in `build/reports/ci/build-execution.json`.
Failed builds cannot claim successful native metrics.
Gradle profiles remain in `build/reports/profile`.
These reports support comparisons on matched source, toolchain, options, and runner capacity.
The native-image optimization level remains unchanged.

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

Install the working tree into the existing user installation from the repository root:

```shell
./packaging/install-checkout.sh persistent --idea-home "/Applications/IntelliJ IDEA.app"
```

This builds the working tree, including uncommitted changes, and verifies the
selected control and IDEA plugin archives. Restart IDEA to load a replaced host.
Kast has one installation at `$HOME/.local/share/kast/installation`; checkout
builds replace those components in place. Session installations and alternate
installation roots are rejected before build or installation effects.

The services have independent release inputs and package tasks:

```shell
./gradlew assembleControlRelease -PcontrolVersion=0.50.0
./gradlew assembleHostRelease -PhostedPluginVersion=0.49.0
```

A control release requires the canonical hosted contract and no host archive.
A host release packages its own runtime dependencies. `assembleRelease` remains
one fresh-install convenience for a selected pair. Changing a control version
does not change the host version or hosted contract.

For a loaded baseline host that provides the complete contract, use
`kast upgrade --control-only`. It admits the live host before retiring control,
activates the candidate and repeats admission. Plugin files and IntelliJ remain
unchanged. Control sessions start fresh. `kast status` separates installed and
running control from each observed host's identity, version and compatibility.

Install a host independently with the public installer's `--host-only --version`
input, then restart IntelliJ through its ordinary lifecycle. The
[mixed-version native check](../runtime/hosted/native/README.md) uses the existing
user installation and normal IntelliJ profile, preserving configuration and
recording exact control, host and process identities. Routine tests alone do
not prove IntelliJ reuse.

Persistent installation stops the previous installed App Server and enables
the new login service. This requires the App Server’s
[Codex prerequisites](../app-server/docs/compatibility.md). Service enablement
failure leaves the installation available and reports failure.

## Validate documentation

From `docs/public`, run the same pinned CLI used by documentation CI:

```shell
npx mint@4.2.841 validate
npx mint@4.2.841 dev --port 3000
```

Open `http://localhost:3000` and check the installation path, navigation, and
examples. Edit the public MDX pages and `docs.json` together. Generated callable
contracts remain owned by the protocol registry.

After changing source-bound knowledge, run from the repository root:

```shell
./gradlew knowledgeImpact verifyKnowledgeBase
```

For a damaged installation, use the [recovery runbook](installation-recovery.md).

### Installer development inputs

The public installer supports `--force`, `--dry-run`, `--version`, `--idea-home`,
`--control-only`, `--host-only`, `uninstall`, and `--help`.
Use `packaging/install-checkout.sh persistent` for checkout builds.
All component installation and lifecycle operations select
`$HOME/.local/share/kast`; an explicit internal root binding must equal that
path. `XDG_DATA_HOME`, custom prefixes and session profiles cannot create
another installation.

Release fixtures supply `KAST_INSTALL_ASSETS_DIRECTORY` with verified archives.
Filesystem fixtures use their own admitted user home and the same canonical
relative layout. Product read limits, endpoint selection and read-only
inspection remain supported. Use `kast ide` for status, refresh, classes,
supertype and completion.

Version-pinned archives must match the installer contract. To stage historical
archives that require retired setup inputs, use their matching tagged installer.
The adjacent-patch acceptance helper supports archives with the current contract.

## Workspace readiness proof boundary

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

The associated IntelliJ modules own the truthfulness of these observations and
native settlement guarantees. Pure tests establish the decisions conditional on
those guarantees; synthetic observations do not qualify IntelliJ behavior.
`WorkspaceEpochValidationTest` enumerates bounded sequences of model movement,
disposal, reopening, readmission, and irrelevant metadata changes. Its independent
oracle tracks fixture state and rejects deliberate stale-snapshot, retirement,
and incarnation-bypass mutations. `HostedWorkspaceReadinessTest` exercises the
production adapter projection with detached epoch sources and no native project,
filesystem, Git, import, or registration fixture.

## OpenWiki upkeep

The repository's architecture concepts live in `openwiki/`. Use the OpenWiki
Codex skill to search and read relevant sections, or follow the quickstart map
when retrieval is unavailable. Recheck important details against source.

Refresh concepts with OpenWiki's begin/plan/next-page/submit-page/finish lifecycle.
Claims and their source versions, category indexes, provenance and run state are
owned by OpenWiki. The existing `knowledgeImpact` and `verifyKnowledgeBase` tasks
now read `openwiki/`: they validate links and source paths and preserve Kotlin PSI
and Python AST declaration checks for `code_sources`. These checks prove source
declaration presence, not compiler resolution or native execution. OpenWiki's
Claims lifecycle separately rechecks changed evidence.

The previous update log is preserved in
[the migration history](reviews/knowledge-migration-history.md). Local native
OpenWiki uses its private subscription login and `gpt-6-luna` setting. Pages
written through Codex MCP use the current chat model. The optional GitHub workflow
runs only on manual dispatch, requires its own API credential, selects Luna and
disables telemetry and tracing. No subscription token belongs in repository
secrets or checked-in files.
