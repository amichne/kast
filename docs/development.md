# Develop and validate Kast

Use Java 25 or newer and the Python version in [`.python-version`](../.python-version).
Read [AGENTS.md](../AGENTS.md) before making changes. The
[knowledge base](../knowledge/index.md) maps architecture to source and tests.

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
For an intentional dependency update, review newly generated checksums against the
publisher before committing the metadata; keep verification enabled for builds and IDE sync.

Choose a local installation from the repository root:

```shell
# Isolated installation, active only in this Bash or Zsh session
source "$(./install.sh --local session)"

# Persistent installation for your user account
./install.sh --local persistent
```

Both modes build the working tree, including uncommitted changes, and verify the
matched control and IDEA plugin archives. Restart IDEA to load the plugin.
Session mode isolates configuration and broker sockets, disables persistent
services, and keeps temporary files under `$KAST_SESSION_ROOT`.

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
It does not establish focus behavior, cold launch into the normal user profile,
or interactive veto and unsaved-editor behavior. See the
[lifecycle verification record](reviews/ide-lifecycle-acceptance.md).
