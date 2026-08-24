# Set up and start Kast

The first successful run establishes one exact repository root and waits for
compiler-backed evidence to become ready. A foreground IDE project is not part
of this workflow.

## Check the host

Kast's supported developer pathway currently requires:

- macOS on Apple silicon
- Java 21
- IntelliJ IDEA 2026.2, build 262, or Android Studio 2026.1.2, build 261
- a Kotlin Gradle repository

The supported JetBrains installation supplies matched IntelliJ and Kotlin
runtime libraries. It can remain closed while Kast runs its own isolated
indexer.

<div class="kast-notice kast-tone-discovery" markdown>

<strong class="kast-notice-title">One install, complete local release</strong>

The installer verifies both release payloads: the `kast` command and its exact
semantic runtime. On first semantic demand, Kast realizes the already local
runtime archive into its content-addressed store. It does not make another
release download.

</div>

## Install Kast

Use the repository installer as the installation boundary. Download it first
so you can inspect the exact script before it runs:

```shell
installer_file="$(mktemp)"
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/amichne/kast/main/install.sh \
  --output "$installer_file"

less "$installer_file"
bash "$installer_file"
```

The installer validates the host and Java version and downloads the latest
stable control and semantic-runtime archives with their SHA-256 records. It
rejects unsafe archive paths or unexpected contents and matches the runtime
archive to the control manifest. It smoke-tests local metadata before it moves
the managed `kast` link.

By default, the command link is `~/.local/bin/kast`. If that directory is not
already on `PATH`, the installer reports the exact directory to add through
your shell profile. The [latest release](https://github.com/amichne/kast/releases/latest)
provides the verified control and semantic-runtime artifacts for manual
inspection.

## Recover from an older installation

If a prior local, Homebrew, or release-managed installation conflicts with the
current release, use the opt-in purge-first flow:

```shell
bash "$installer_file" install --purge-existing
```

The installer downloads and verifies the replacement before it removes Kast
state. It then stops Kast indexers and launch services, removes current and
historical commands, Homebrew installation, runtime state, configuration, and
old Kast IDE plugins, and installs the complete release. It does not remove
repositories or unrelated JetBrains state.

To remove Kast without reinstalling it, run:

```shell
bash "$installer_file" uninstall
```

Confirm the process-local contract before starting a runtime:

```console
kast --version
kast --schema
```

`kast --schema` lists the twelve canonical operations, command shapes, runtime
identity, and wire schema without touching the runtime store.

## Start the exact repository

Run Kast from the canonical repository root:

```console
cd /path/to/kotlin-repository
kast start
```

Kast now resolves the supported platform installation, starts or reuses the
indexer for this root, imports the Gradle model, and waits for semantic
readiness. Success is one JSON result for `workspace.inspect`. A qualified
result includes the evidence that is ready and the limitation that remains.

Read lifecycle state without asking a semantic question:

```console
kast status
```

The status is `running`, `stopped`, or `stale` for this root. If Kast reports a
blocker, use the returned condition as the next action. A missing supported
installation, a rejected project model, and an unavailable compiler scope are
different failures and remain different in the output.

## Make the first answer useful

Start with workspace evidence, then refine only as far as the decision needs:

```console
kast workspace inspect
kast symbol discover --mode name --query PricePolicy --limit 25
```

The first command tells you what is ready. The second returns bounded
candidates, not an exact declaration claim.

[Inspect workspace readiness](questions/workspace-readiness.md) ·
[Establish declaration identity](questions/declaration-identity.md)
