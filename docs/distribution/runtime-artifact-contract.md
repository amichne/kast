---
title: Runtime artifact contract
description: Prebuilt Linux headless runtime artifacts for Devin blueprints and
  other ephemeral agent workspaces.
icon: lucide/package-check
---

# Runtime Artifact Contract

This contract defines the prebuilt Linux x64 runtime path used by Devin
blueprints and other short-lived agent workspaces. The goal is to install Kast
from immutable artifacts and validate the install locally before spending a full
release cycle on a remote snapshot.

Use the [setup-kast action](setup-kast-action.md) page for blueprint snippets,
action inputs, credentials, and operator verification. This page owns the
artifact shape that release jobs, cache jobs, and setup clients must agree on.

## Artifact set

Release workflows publish these files as public, version-locked GitHub release
assets. The scheduled cache seed workflow may publish the same cache shape as a
temporary Actions artifact for intermediate snapshots, but published releases
carry the cache beside the runtime so setup clients can use public URLs without
artifact-store credentials.

```text
kast-headless-linux-x64.tar.zst
kast-headless-linux-x64.sha256
kast-runtime-manifest.json
gradle-ro-dep-cache.tar.zst
gradle-ro-dep-cache.sha256
```

`kast-runtime-manifest.json` is a sidecar, not only a file inside the tarball.
That is intentional: a manifest cannot reliably contain the SHA-256 digest of
the archive that contains the manifest itself. `setup-kast` validates the
sidecar against the runtime tarball digest, then copies the manifest into the
installed runtime directory.

## Runtime layout

The runtime archive extracts into a directory that can be copied directly under
the managed install version. `setup-kast` installs it at
`/opt/kast/<version>` by default and maintains `/opt/kast/current`.

```text
/opt/kast/
  current -> /opt/kast/<version>
  <version>/
    bin/kast
    lib/runtime-libs/
    idea/
    plugins/
    kast-runtime-manifest.json
  cache/
    gradle-ro/
      modules-2/
```

The action also writes a managed `config.toml` under `KAST_CONFIG_HOME`, points
the headless backend at `/opt/kast/current/lib/runtime-libs` and
`/opt/kast/current/idea`, and exports `KAST_CACHE_HOME`. Daemon descriptors,
sockets, and logs are resolved under `KAST_CACHE_HOME/workspaces/<workspace-id>`
when a workspace starts.
When that socket path would exceed Unix-domain socket path limits, the CLI keeps
descriptors and logs under `KAST_CACHE_HOME` and falls back to a short
`/tmp/kast-<hash>.sock` socket path.

## Manifest

The manifest schema is checked in at
[`docs/distribution/kast-runtime-manifest.schema.json`](kast-runtime-manifest.schema.json).
Every setup client must validate the exact schema before trusting an artifact:
missing fields, unsupported fields, type mismatches, wrong versions, wrong
platforms, and digest mismatches are installation failures.

```json
{
  "schemaVersion": 1,
  "kastVersion": "1.0.0",
  "kastGitSha": "0123456789abcdef",
  "os": "linux",
  "arch": "x64",
  "javaVersion": "21",
  "intellijBuild": "2025.3",
  "kotlinPluginVersion": "2.3.21",
  "kastIndexSchemaVersion": "7",
  "artifactSha256": "<sha256>"
}
```

## Setup clients

`setup-kast` is the supported setup client for this artifact set. It is a
Node 20 action under `setup-kast/` because Devin blueprints run GitHub Action
steps and carry `GITHUB_ENV` and `GITHUB_PATH` writes into later steps.

The action page owns invocation details, input defaults, credential handling,
and verification commands. Any new setup client must preserve the same manifest
validation, checksum validation, archive-safety checks, config layout, and
workspace-local daemon-state boundary before it is treated as equivalent.

## Gradle cache

The read-only cache artifact must contain `gradle-ro/modules-2` at the archive
root. Lock files and Gradle GC metadata are excluded before packaging, and
`setup-kast` rejects cache artifacts that do not keep that shape.

```bash
export GRADLE_USER_HOME="$RUNNER_TEMP/gradle-seed"
./gradlew dependencies --no-daemon
./gradlew buildEnvironment --no-daemon
scripts/package-gradle-ro-cache.sh \
  --gradle-user-home "$GRADLE_USER_HOME" \
  --output dist/gradle-ro-dep-cache.tar.zst
```

`setup-kast` installs the cache under `/opt/kast/cache/gradle-ro` and exports
`GRADLE_RO_DEP_CACHE` plus a writable `GRADLE_USER_HOME=$HOME/.gradle`. The
installed cache tree is made non-writable after extraction so sessions cannot
silently mutate the snapshot seed.

`scripts/verify-setup-kast-install.sh` fails if `command -v kast` resolves to a
different binary than `<install-dir>/bin/kast`. Keep `/opt/kast/current/bin`
ahead of any global Kast install in blueprint `PATH` setup so snapshots do not
silently exercise a stale machine-level binary.

## Contract verification

Artifact changes need proof at three boundaries: packaging, action install, and
external snapshot build. Keep fast fixture checks close to `setup-kast`, then
prove the real runtime path before publication.

```bash
.github/scripts/test-devin-artifact-packagers.sh
.github/scripts/test-setup-kast-action.sh
.github/scripts/test-setup-kast-real-artifacts.sh
.github/scripts/test-devin-snapshot-build-verifier.sh
```

CI also has a `setup-kast runtime artifact` job. It consumes the real Linux CLI
and headless backend artifacts from earlier CI jobs, packages the runtime,
invokes `setup-kast` as a local GitHub Action, runs
`scripts/verify-setup-kast-install.sh`, starts the installed headless backend on
a tiny Kotlin workspace, and verifies that daemon state stays out of the install
tree. The verifier also passes `--gradle-root "$GITHUB_WORKSPACE"` so CI proves
a repo-level Gradle warm step against the installed read-only cache.

The GitHub CI loop proves the action/runtime contract before publication, but a
real Devin snapshot is still an external async build. Use
`scripts/verify-devin-snapshot-build.sh` when a service-user credential is
available, then run the final acceptance commands in a fresh Devin session
because only that session can prove the installed `kast`, Gradle cache, and
workspace-local daemon state from the booted snapshot.
