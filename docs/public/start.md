# Set up and start Kast

The first successful run admits the already-open IntelliJ Project for one exact
repository root and uses its compiler-backed evidence.

## Check the host

Kast's supported developer pathway currently requires:

- macOS on Apple silicon
- Java 21
- IntelliJ IDEA 2026.2 or Android Studio 2026.1.2 with the repository already open and indexed
- a Kotlin Gradle repository

The supported IDE supplies the existing Project, VFS, indexes, PSI, and Kotlin
semantic APIs. Kast does not construct a second Project or isolated indexer.

<div class="kast-notice kast-tone-discovery" markdown>

<strong class="kast-notice-title">One install, complete local release</strong>

The installer verifies both release payloads: the `kast` command and its exact
standalone IDE plugin. No semantic-runtime archive, private IDEA home, or
runtime store is installed.

</div>

## Install Kast

Use the repository installer as the installation boundary:

```shell
curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh | bash
```

The installer validates the host and Java version and downloads the latest
stable control archive and matched IDE plugin with their SHA-256 records. It
rejects unsafe archive paths or unexpected contents and smoke-tests local
metadata before it moves the managed command and plugin links.

By default, the command link is `~/.local/bin/kast`. If that directory is not
already on `PATH`, the installer reports the exact directory to add through
your shell profile. The [latest release](https://github.com/amichne/kast/releases/latest)
provides the verified control and IDE-plugin artifacts for manual
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

Confirm the process-local contract before contacting the IDE:

```console
kast --version
kast --schema
```

`kast --schema` lists the canonical operations, command shapes, and wire schema
without contacting IntelliJ.

## Start the exact repository

Run Kast from the canonical repository root:

```console
cd /path/to/kotlin-repository
kast start
```

Kast now admits the compatible exact-root endpoint published by the already
running IDE. It never opens a Project, imports Gradle, refreshes VFS, or starts
an isolated process. Success is one JSON result for `workspace.inspect`; a
qualified result retains the exact limitation.

Read lifecycle state without asking a semantic question:

```console
kast status
```

The status reflects hosted endpoint availability for this root. If Kast reports
a blocker, use the returned condition as the next action. A missing plugin, a
missing exact Project, and an incompatible endpoint remain different failures.

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
