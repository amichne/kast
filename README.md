# Kast

Kast gives coding agents compiler-grounded search over one exact Kotlin
repository. It resolves declarations and relationships that text search can only
approximate, while retaining the scope and limits behind each answer.

Developers install Kast and connect it to an agent harness. Kast owns runtime
readiness, workspace synchronization, and intermediate semantic operations.

[Install and connect Kast](https://kast.michne.com/start/) ·
[Search with Kast](https://kast.michne.com/search/) ·
[Integrate an agent harness](https://kast.michne.com/agent-harnesses/) ·
[Troubleshoot Kast](https://kast.michne.com/troubleshooting/) ·
[Explore the source-bound knowledge base](knowledge/index.md)

## Existing IDEA index

The primary indexing commands in this checkout are `kast index classes <name>`
and `kast index supertype <qualified-name>`. They provide bounded compiler-resolved
class discovery in an already open IDEA project. It uses that project's Kotlin
index and saved content through the separate hosted plugin. Missing IDE state
returns unavailability. Build and install the plugin following the
[existing-IDE endpoint runbook](experiments/host-observation/HOSTED_ENDPOINT.md),
then use `kast index status --root /path/to/repository` and
`kast index classes Refinement --root /path/to/repository`.

IDEA owns index updates. These commands require no Kast index synchronization,
separate workspace, copied index storage, or Python runtime. The earlier `kast ide`
spelling remains available through the same command implementation.

The hosted command runs before isolated-runtime bootstrap. General semantic
commands and App Server queries retain their existing runtime and publication
contracts; the hosted class answer explicitly carries its narrower provenance.

Read the [detailed HTML implementation review](docs/reviews/hosted-indexing.html)
for the architecture, qualification evidence, and remaining migration work.

## Install

Kast currently requires:

- macOS on Apple silicon;
- an on-disk Kotlin Gradle repository; and
- IntelliJ IDEA `262.*` with its bundled Kotlin plugin and Java 25 JBR.

Install the latest published release:

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The installer discovers the supported IntelliJ runtime, verifies the matched
Kast payloads, installs the existing-IDE plugin for the `262` release line, and installs the
integration entrypoints. Restart IDEA after installation to activate the plugin. If discovery is
ambiguous, select IDEA explicitly:

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" \
  -- --idea-home "/Applications/IntelliJ IDEA.app"
```

See [Install and connect Kast](https://kast.michne.com/start/) for the complete
host contract and uninstall path.

To build and install the current checkout, run one of these from its root:

```shell
# Isolated installation, activated only in this Bash or Zsh session:
source "$(./install.sh --local session)"

# Persistent installation into your configured KAST_* paths:
./install.sh --local persistent
```

Both modes build the working tree, including uncommitted changes, and verify
the matched control and semantic runtime archives. Session mode isolates its
configuration, caches and sockets and disables persistent services. Repeated
activation is safe; its temporary files remain under `$KAST_SESSION_ROOT`.

Persistent mode honors `KAST_INSTALL_ROOT` and `KAST_BIN_DIR`, writes a fresh
release-local runtime configuration, and enables launchd indexer ownership. It
stops the previous installed App Server before activation and enables the new
App Server login service for this checkout.
This requires the App Server's Codex prerequisites. If service enablement fails,
the installation remains available and the command reports failure. The
installation persists for your user account across sessions; it is not an
all-users system daemon. Run it from a shell without an active Kast session.

## Connect an agent

Kast includes a Codex integration. Start it from the Kotlin repository the agent
will inspect:

```console
cd /path/to/kotlin-repository
kast codex
```

`kast codex` now enrolls the current workspace, installs or refreshes the login
bootstrap, recovers stale owned broker state, and starts the persistent service
before launching Codex. `kast codex desktop` performs the same preparation and
attaches to that service. Client closure leaves the service running. Use
`kast app-server status`, `stop`, or `disable` to manage its lifecycle.

`kast app-server status` reports the exact service-log and resolved launch-
environment paths. Set `KAST_DEBUG=1` for bounded launch stages on the calling
process's stderr. If normal ownership recovery cannot converge, the explicit
`kast app-server repair --destructive` command deletes only the active
installation's owned runtime, cache, broker, and workspace-registry state,
re-enrolls the current workspace, and starts clean.

Desktop build-specific discovery is checked, but full desktop compatibility is
still unqualified. See the module's [compatibility and blocker record](app-server/docs/compatibility.md)
for exact evidence and the outstanding real-client release gate.

Kast qualifies the installed tool contract before a thread starts. The default
catalog includes eager `search_classes`, `search_functions`,
`search_declarations`, and `check_diagnostics`, plus deferred `query_symbols`,
source, relation, and impact tools. Direct symbol lookup/inspection and the
three change tools require explicit selection. `change_plan` prepares an
immutable `AddDeclaration` plan without writing source. `change_apply` and
`change_recover` require approval of that exact stored plan. Enroll the local
broker with `kast ide trust-broker` before using either operation. Change tools
remain excluded from defaults until the installed provider → CLI → plugin
workflow passes native acceptance.

See [Add a Kotlin declaration](docs/public/change.mdx) for saved-content
requirements, the approval workflow, verification outcomes and durable recovery.

Other harnesses should consume the exact installed `serverProjection`; they
should not copy command names, schemas, or selection policy into another
configuration. See [Integrate an agent harness](https://kast.michne.com/agent-harnesses/).

## Search first

Use `kast.search_classes` for class-like declarations, `kast.search_functions`
for functions and methods, and `kast.search_declarations` for unknown or mixed
kinds, properties and type aliases. Exact matching is the default. The deferred
`kast.query_symbols` supports enumeration, returned references, ordered filters
and relation expansion while preserving compiler identity between stages.

Agents should use specialist reads only when their narrower contract is needed.
They should not start the runtime, synchronize the workspace, or build topology
as prerequisites. Each semantic request acquires the evidence it needs.

## Know when something went wrong

Kast never turns partial or unknown state into an unqualified answer:

- **Complete** means the request met its declared scope and limits.
- **Qualified** means the returned evidence is useful only with the attached
  limitation.
- **Rejected** means Kast established no successful semantic payload.

The harness should keep these outcomes visible. If the integration cannot start
or a request rejects, follow [Troubleshoot Kast](https://kast.michne.com/troubleshooting/).
Bare `kast`, run from the repository root, is a passive support command that
reports local runtime and bootstrap state without starting or repairing it.

Hosted semantic reads log bounded diagnostic records to the IDE log by default.
See [read limits and diagnostics](docs/hosted-read-configuration.md) for the 45 tunable settings and activation instructions.

## Develop Kast

Development requires Java 25 or newer and the Python version in
[`.python-version`](.python-version).

```shell
./gradlew build
./gradlew assembleSidecarRelease
```

The opt-in native change acceptance task stages the matched CLI, broker and
plugin, then creates and imports a private Kotlin fixture. Supply an IDEA
installation, the generated JSON schema directory from the installed Codex
version, and a new report path:

```shell
./gradlew hostedChangeAcceptance \
  -PhostedIdeaHome=/absolute/path/to/idea \
  -PhostedCodexSchemas=/absolute/path/to/codex-schemas \
  -PhostedChangeReport=/absolute/path/to/new-change-receipt.json
```

Release qualification requires a clean source checkout. The diagnostic option
`-PhostedDiagnosticDirty=true` permits development runs and records them as
unqualified. The receipt separates native observations from deterministic tests
and stock Codex desktop compatibility. Fixture setup and retirement stay outside
the measured change operations.

The [build and release reuse audit](docs/reviews/build-release-cache-audit.md) records
artifact retrieval, cache policy and deferred promotion work.

Validate the public documentation with `mint validate` from `docs/public`.

## Security and license

Report vulnerabilities through [GitHub private vulnerability
reporting](https://github.com/amichne/kast/security/advisories/new). Kast is
available under the [MIT License](LICENSE).
