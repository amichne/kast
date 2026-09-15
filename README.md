# Kast

Kast gives coding agents compiler-grounded search over one exact Kotlin
repository. It resolves declarations and relationships that text search can only
approximate, while retaining the scope and limits behind each answer.

Developers install the Kast IDEA plugin and connect its CLI to an agent harness.
Kast reads the open project through compiler-backed services; IDEA owns workspace
import, synchronization and incremental indexes.

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

All semantic commands and App Server tools use this existing-IDE authority.
The shipped graph has no isolated indexer, second Gradle importer, copied index
storage, or retained topology backend. Durable change plans, recovery journals
and receipts remain available for approved source changes. The topology contract,
build, service, IntelliJ extraction, and SQLite implementation remain buildable
for upcoming graph work, outside the shipped runtime dependencies.

Read the [detailed HTML implementation review](docs/reviews/hosted-indexing.html)
for the architecture, qualification evidence, and remaining migration work.

## Installed Kast knowledge

`kast knowledge KastCli` searches the installed documentation for Kast's own
public Kotlin declarations. Pass a returned `resource` to the same command to
read its declaration header and KDoc. `kast knowledge manifest.json` lists the
module and guide resources; `kast knowledge guides/root.json` reads the root
guidance.

These lookups use the installed bundle from any directory, with no checkout,
open IDE, Gradle invocation or network lookup. Results carry explicit Kotlin PSI
syntax evidence and its limits. See the [installed knowledge contract](knowledge/contracts/installed-knowledge.md).

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
integration entrypoints. It also registers and starts the per-user App Server through launchd.
Restart IDEA after installation to activate the plugin. If discovery is
ambiguous, select IDEA explicitly:

```shell
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" \
  -- --idea-home "/Applications/IntelliJ IDEA.app"
```

Choose any absolute user-owned installation and command directories with
`--install-root` and `--bin-dir`. If either command path is occupied by an
unrelated file, an interactive install lists the exact collisions and asks
before removing them. Automated installs fail closed unless
`--clean-collisions` is passed explicitly.

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
the matched control and IDEA plugin archives. Session mode isolates its
configuration and broker sockets and disables persistent services. Repeated
activation is safe; its temporary files remain under `$KAST_SESSION_ROOT`.

Persistent mode honors `KAST_INSTALL_ROOT` and `KAST_BIN_DIR`, writes a fresh
release-local broker configuration. It
stops the previous installed App Server before activation and enables the new
App Server login service for this checkout.
This requires the App Server's Codex prerequisites. If service enablement fails,
the installation remains available and the command reports failure. The
installation persists for your user account across sessions; it is not an
all-users system daemon. Run it from a shell without an active Kast session.

## Installation limits and clean recovery

Control installation and runtime identity admission share a 16,384-entry traversal
budget across `bin`, `lib`, and `share`, including those three directories. The
payload also has separate file and byte budgets. These count Kast distribution
files, not repository symbols. Version 0.39.3 raised the installer ceiling while
leaving the broker at 4,096; the broker now uses the shared owner. Rejections
identify the resource, maximum, and observed lower bound. Release assembly runs
runtime admission against the staged control product before publishing assets.

Each installation saves an offline recovery executable and an ownership receipt
under `<install-root>/recovery/<version-and-digest>/`. The receipt is written before
activation changes. Plugin replacement retains the previous plugin directory as
a baseline; recovery never deletes retained payloads or uncertain state.

To inspect and detach a damaged installation, use its saved executable directly:

```console
python3 /absolute/install-root/recovery/version-and-digest/installation-recovery.py detach --installation /absolute/install-root/versions/version-and-digest --dry-run
python3 /absolute/install-root/recovery/version-and-digest/installation-recovery.py detach --installation /absolute/install-root/versions/version-and-digest
```

For an older installation without a receipt, use `installation-recovery.py` from
a checksum-verified newer control distribution. First run `prepare` with the exact
`--installation` and `--bin-directory`. An explicit `--plugin-root` can prove an
empty plugin location; an existing unreceipted plugin remains unproven and is
preserved. Then run `detach` using the printed `recoveryExecutable` path. Do not
execute a recovery script obtained from a damaged, unverified payload.

`CleanBaselineRestored` means retirement was verified and the selected installation
was detached. `DetachedWithUnresolvedState` means verified integration was detached
but processes, plugin ownership, an IDE restart, or source-change evidence remain
unresolved; this returns a nonzero exit status. `RecoveryBlocked` means ownership,
locking, or filesystem conditions prevented completion. Preserve the receipt and
retry after resolving the reported condition. Dry-run is passive and does not
claim retirement. Recovery never signals an unproven PID or discards a mutation
journal. Retired state is quarantined only after verification; otherwise it stays
in place behind a launch fence. Restart IDEA after plugin detachment. Disk loss or
revoked filesystem permissions cannot be repaired by these commands.

## Connect an agent

Kast includes a Codex integration. Start it from the Kotlin repository the agent
will inspect:

```console
cd /path/to/kotlin-repository
kast codex
```

The installer owns registration and the per-user launchd lifecycle. `kast codex`
enrolls the current workspace, verifies that managed service, recovers stale
owned broker state, and launches Codex. `kast codex desktop` performs the same
preparation and attaches to that service. Client closure leaves the service
running. Low-level app-server, IDE, and index lifecycle commands are internal
implementation details and are omitted from the public CLI surface.

Desktop build-specific discovery is checked, but full desktop compatibility is
still unqualified. See the module's [compatibility and blocker record](app-server/docs/compatibility.md)
for exact evidence and the outstanding real-client release gate.

Kast qualifies the installed tool contract before a thread starts. The default
catalog includes eager `search_classes`, `search_functions`,
`search_declarations`, and `check_diagnostics`, plus deferred `query_symbols`,
source, relation, impact, and change tools. Direct symbol lookup/inspection
requires explicit selection. `change_plan` prepares an
immutable `AddDeclaration` plan without writing source. `change_apply` and
`change_recover` require approval of that exact stored plan. Enroll the local
broker with `kast ide trust-broker` before using either operation. The installed
provider → CLI → plugin workflow passed the
[native change acceptance matrix](docs/reviews/plugin-native-change-acceptance.md).

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
reports the product version, existing-IDE authority and discovered repository
root. Use `kast ide status` for the actual project endpoint. Retired `kast start`
and `kast stop` commands reject; IDEA owns the project lifetime.

Hosted semantic reads log bounded diagnostic records to the IDE log by default.
Records distinguish transaction evaluation, complete answers, qualified answers
and rejection. Change-storage rejections preserve database, plan, receipt and
lookup causes in `HOST_REJECTED.detail`, including corrupt and incompatible rows.
See [read limits and diagnostics](docs/hosted-read-configuration.md) for the 45 tunable settings and activation instructions.

## Develop Kast

Development requires Java 25 or newer and the Python version in
[`.python-version`](.python-version).

```shell
./gradlew build
./gradlew assembleRelease
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
