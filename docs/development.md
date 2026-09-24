# Develop and validate Kast

Use Java 25 or newer and the Python version in [`.python-version`](../.python-version).
Read [AGENTS.md](../AGENTS.md) before making changes. The
[knowledge base](../knowledge/index.md) maps architecture to source and tests.

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
| Native product composition | Existing opt-in suite in an explicitly disposable/authorized environment | Actual IDEA/plugin lifecycle or service registration |

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

Report the command, observed result and evidence level: pure policy, private
filesystem/process adapter, compiler/platform fixture or native composition. Name
skips and anything not verified. The [configuration and retirement verification
record](reviews/behavior-sized-testing.md) contains a concrete migration inventory,
complete selectors, resource reductions and observed limits.

## Build and install the checkout

Prepare the same Python test dependencies used by CI before running Gradle:

```shell
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r experiments/host-observation/requirements-test.txt
```

Keep that environment active for the build. Use a fresh Gradle process so Python
checks inherit its executable search path; a daemon started before activation can
retain the earlier interpreter.

```shell
./gradlew --no-daemon build
./gradlew --no-daemon assembleRelease
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

Choose a local installation from the repository root:

```shell
# Isolated installation, active only in this Bash or Zsh session
source "$(./packaging/install-checkout.sh session --idea-home "/Applications/IntelliJ IDEA.app")"

# Persistent installation for your user account
./packaging/install-checkout.sh persistent --idea-home "/Applications/IntelliJ IDEA.app"
```

Both modes build the working tree, including uncommitted changes, and verify the
matched control and IDEA plugin archives. Restart IDEA to load the plugin.
Session mode isolates configuration and broker sockets, disables persistent
services, and keeps temporary files under `$KAST_SESSION_ROOT`.
It still stages the Kast plugin in the selected IDEA profile. Restore the
persistent plugin before the next IDEA restart when leaving a session install.

Run persistent installation from a shell without an active Kast session. It
honors `KAST_INSTALL_ROOT` and `KAST_BIN_DIR`, stops the previous installed App
Server, and enables the new login service. This requires the App Server’s
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

## Run native change acceptance

The opt-in task stages matched CLI, broker, and plugin artifacts, then creates
and imports a private Kotlin fixture. Supply an IDEA installation, generated
JSON schemas from the installed Codex version, and a new report path:

```shell
./gradlew hostedChangeAcceptance \
  -PhostedIdeaHome=/absolute/path/to/idea \
  -PhostedCodexSchemas=/absolute/path/to/codex-schemas \
  -PhostedChangeReport=/absolute/path/to/new-change-receipt.json
```

Release qualification requires a clean checkout. For a development run,
`-PhostedDiagnosticDirty=true` permits dirty source and records it as unqualified.
The receipt distinguishes native observations, deterministic tests, and stock
Codex Desktop compatibility. See the [native change acceptance
record](reviews/plugin-native-change-acceptance.md) for the tested boundary.

For a damaged installation, use the [recovery runbook](installation-recovery.md).

## Run native lifecycle smoke

This is the designated native lifecycle composition entry point for the focused
configuration/retirement examples. It has no direct Gradle task; the commands below
build its inputs and invoke it explicitly. Run native exploration only in a
disposable/authorized environment, with no fallback to a developer installation.

Build the matched development artifacts, then use the existing disposable graphical
IDEA fixture. This stages a private profile and two trusted fixture roots; it does
not control your regular IDEA process or profile.

```shell
./gradlew stageKastControlProduct :runtime:hosted:hostedPlugin
python3 packaging/run-hosted-lifecycle-smoke.py \
  --idea-home '/absolute/path/IntelliJ IDEA.app/Contents' \
  --product build/control-product \
  --plugin runtime/hosted/build/distributions/kast-ide-hosted-v<VERSION>-idea-262.zip \
  --report /absolute/path/to/lifecycle-smoke.json
```

Use the version from the staged product metadata. The bounded smoke checks
zero-project control, existing-settings import, first linking, separate project
identities, reuse, reload, release, normal closure, and retained closure status.
It does not establish fresh public installation, launchd registration, plugin
restart, focus behavior, cold launch into the normal user profile, or interactive
veto and unsaved-editor behavior. See the
[lifecycle verification record](reviews/ide-lifecycle-acceptance.md).

### Installer development inputs

The public installer supports `--force`, `--dry-run`, `--version`, `--idea-home`,
`uninstall`, and `--help`. Deprecated collision flags, custom directory flags,
and `--local` are rejected. Use `packaging/install-checkout.sh session|persistent`
for checkout builds. Session mode creates private paths and prints an activation
file; persistent mode installs and activates the same complete suite.

Release fixtures supply `KAST_INSTALL_ASSETS_DIRECTORY` with verified archives.
Only this explicit development input permits `KAST_INSTALL_ROOT`, `KAST_BIN_DIR`,
and `KAST_INSTALL_PROFILE=session`. Product read limits, endpoint selection, and
read-only inspection remain supported. The duplicate `index` command family is
removed; use `kast ide` for status, refresh, classes, supertype, and completion.

Version-pinned archives must match the installer contract. To stage historical
archives that require retired setup inputs, use their matching tagged installer.
The adjacent-patch acceptance helper supports archives with the current contract.
