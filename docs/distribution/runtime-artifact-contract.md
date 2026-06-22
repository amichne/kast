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

The sibling
[`kast-action`](https://github.com/amichne/kast-action) repository owns the
GitHub Action implementation and detailed action usage. This page owns the
artifact shape that release jobs, cache jobs, and action consumers must agree
on.

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
the archive that contains the manifest itself. `kast-action` validates the
sidecar against the runtime tarball digest, then copies the manifest into the
installed runtime directory.

## Runtime layout

The runtime archive extracts into a directory that can be copied directly under
the managed install version. Action consumers install it at
`/opt/kast/<version>` by default, write `/opt/kast/install.json`, and maintain
`/opt/kast/current`.

```text
/opt/kast/
  current -> /opt/kast/<version>
  install.json
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

The action also writes the Kast install manifest under `KAST_INSTALL_ROOT`.
That manifest points the headless backend at `/opt/kast/current/lib/runtime-libs`
and `/opt/kast/current/idea`, records the active binary, and exports
`KAST_CACHE_HOME`. Behavior config under `KAST_CONFIG_HOME` remains optional
and must not own install paths. Daemon descriptors, sockets, logs, locks,
runtime state, and workspace state are resolved from the manifest-backed path
model when a workspace starts.

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

## Action compatibility

`kast-action@v2` is the supported action line for this artifact set. The action
is intentionally low-level: callers provide the runtime artifact URL, runtime
SHA-256, manifest URL, optional Gradle cache URL, and optional Gradle cache
SHA-256. That keeps enterprise mirrors explicit and keeps artifact publication
separate from action publication.

The `kast` monorepo owns the release artifacts and keeps one CI smoke that
installs those artifacts through `amichne/kast-action@v2`. The monorepo must
not contain a GitHub coding-agent setup workflow, publish an in-repository
action copy, or document action inputs beyond the compatibility boundary here.

The Ubuntu/Debian server bundle is a separate install surface. It is packaged
with `kast package ubuntu-debian-bundle` and activated by
`kast install activate-bundle`; `scripts/install-ubuntu-debian.sh` is only the
bootstrap and compatibility entrypoint for that bundle. Do not treat the server
bundle as a replacement for the Marketplace action inputs unless the client is
intentionally running the server installer path.

Any new action consumer must preserve the same runtime
manifest validation, install manifest activation, checksum validation,
archive-safety checks, and workspace-local daemon-state boundary before it is
treated as equivalent.

## Gradle cache

The read-only cache artifact must contain `gradle-ro/modules-2` at the archive
root. Lock files and Gradle GC metadata are excluded before packaging, and
`kast-action` rejects cache artifacts that do not keep that shape.

```bash
export GRADLE_USER_HOME="$RUNNER_TEMP/gradle-seed"
./gradlew dependencies --no-daemon
./gradlew buildEnvironment --no-daemon
scripts/package-gradle-ro-cache.sh \
  --gradle-user-home "$GRADLE_USER_HOME" \
  --output dist/gradle-ro-dep-cache.tar.zst
```

`kast-action` installs the cache under `/opt/kast/cache/gradle-ro` and exports
`GRADLE_RO_DEP_CACHE` plus a writable `GRADLE_USER_HOME=$HOME/.gradle`. The
installed cache tree is made non-writable after extraction so sessions cannot
silently mutate the snapshot seed.

`scripts/verify-setup-kast-install.sh` fails if `command -v kast` resolves to a
different binary than `<install-dir>/bin/kast`. Keep `/opt/kast/current/bin`
ahead of any global Kast install in hosted-workspace `PATH` setup so snapshots
do not silently exercise a stale machine-level binary.

## Contract verification

Artifact changes need proof at three boundaries: packaging, action
compatibility, and external snapshot build. The action repository owns its own
fixture tests; this monorepo proves that current artifacts still install
through `kast-action@v2`.

```bash
.github/scripts/test-devin-artifact-packagers.sh
./scripts/smoke-ubuntu-debian-bundle.sh
.github/scripts/test-devin-snapshot-build-verifier.sh
```

CI also has a `kast-action runtime contract` job. It consumes the real Linux
CLI and headless backend artifacts from earlier CI jobs, packages the runtime,
invokes `amichne/kast-action@v2`, runs
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
