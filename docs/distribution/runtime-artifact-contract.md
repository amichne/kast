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

## Artifact set

Release and cache workflows publish these files as the Phase 1 setup surface.
The runtime and Gradle cache are separate because the runtime is versioned with
Kast, while the dependency cache can refresh independently.

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

## Setup action

`setup-kast` is a Node action because Devin blueprints currently run Node
actions and propagate `GITHUB_ENV` and `GITHUB_PATH` writes to later blueprint
steps. Docker and composite actions are not part of this setup path.

```yaml
initialize:
  - name: Install JDK 21
    uses: github.com/actions/setup-java@v4
    with:
      java-version: "21"
      distribution: "temurin"

  - name: Install Gradle support
    uses: github.com/gradle/actions/setup-gradle@v4

  - name: Install artifact decompression tools
    run: |
      if ! command -v zstd >/dev/null 2>&1; then
        sudo apt-get update
        sudo apt-get install -y --no-install-recommends zstd
      fi

  - name: Install Kast headless runtime
    uses: github.com/amichne/kast/setup-kast@v1
    with:
      version: "1.0.0"
      artifact-url: "$KAST_HEADLESS_URL"
      artifact-sha256: "$KAST_HEADLESS_SHA256"
      manifest-url: "$KAST_RUNTIME_MANIFEST_URL"
      authorization-header: "$KAST_ARTIFACT_AUTHORIZATION_HEADER"
      gradle-ro-cache-url: "$KAST_GRADLE_RO_CACHE_URL"
      gradle-ro-cache-sha256: "$KAST_GRADLE_RO_CACHE_SHA256"
      gradle-ro-cache-authorization-header: "$KAST_GRADLE_CACHE_AUTHORIZATION_HEADER"
      install-dir: "/opt/kast"
      download-attempts: "3"
      download-retry-delay-ms: "1000"

  - name: Persist Kast environment
    run: |
      export KAST_HOME=/opt/kast/current
      export PATH=/opt/kast/current/bin:$PATH
      export KAST_CACHE_HOME=$HOME/.cache/kast
      export KAST_CONFIG_HOME=$HOME/.config/kast
      export GRADLE_RO_DEP_CACHE=/opt/kast/cache/gradle-ro
      export GRADLE_USER_HOME=$HOME/.gradle

      {
        echo 'export KAST_HOME=/opt/kast/current'
        echo 'export PATH=/opt/kast/current/bin:$PATH'
        echo 'export KAST_CACHE_HOME=$HOME/.cache/kast'
        echo 'export KAST_CONFIG_HOME=$HOME/.config/kast'
        echo 'export GRADLE_RO_DEP_CACHE=/opt/kast/cache/gradle-ro'
        echo 'export GRADLE_USER_HOME=$HOME/.gradle'
      } >> "$ENVRC"

      command -v kast
      kast --version
      kast doctor
      test -L /opt/kast/current
      test -f /opt/kast/current/kast-runtime-manifest.json
      test -d /opt/kast/cache/gradle-ro/modules-2
      test -n "${GRADLE_RO_DEP_CACHE:-}"
      test -n "${GRADLE_USER_HOME:-}"
```

The `uses` reference follows Devin's GitHub Action format:
`github.com/<owner>/<repo>/<subpath>@<ref>`. Pin it to a tag that contains
`setup-kast/action.yml` and `setup-kast/dist/index.js`; use a full commit SHA
only for temporary test snapshots before the tag exists.

`manifest-url` is the deliberate addition to the initial plan. It makes the
runtime digest verifiable without inventing a self-referential tarball digest.
`version` must be a semver path segment such as `1.2.3`, `v1.2.3`, or
`1.2.3-beta.1`; the action rejects path-like values before computing an install
target.
`strict` defaults to `true`; set `strict: "false"` only when a snapshot should
continue after `kast doctor` reports a non-terminal environment issue.
The action requires `tar` and `zstd` on `PATH` because runtime and cache
artifacts use `.tar.zst`; it preflights those tools before downloading or
mutating the install directory.
Artifact downloads retry bounded transient failures by default. Increase
`download-attempts`, `download-retry-delay-ms`, or `download-timeout-ms` only
for slower internal artifact stores.
HTTP artifacts are streamed to disk and checksummed from disk, so installing the
runtime does not require buffering the full archive in Node.js memory.
Use `authorization-header` for private artifact stores that require an HTTP
`Authorization` header. `artifact-authorization-header`,
`manifest-authorization-header`, and `gradle-ro-cache-authorization-header`
override that default when different stores use different credentials. The
action never prints full HTTP artifact URLs in retry or failure messages, so
signed URL query strings are not exposed in logs. Prefer header-based
credentials where the artifact store supports them; signed URLs still work, but
they should be short-lived.

Set `KAST_WORKSPACE_ID` in repo-level maintenance only when the organization
has a stable workspace identifier. Without it, the CLI derives a workspace hash
from the resolved workspace root and stores descriptors, sockets, and logs
under `KAST_CACHE_HOME/workspaces/<hash>`.
If the resulting socket path would be too long for the operating system, only
the socket moves to the short temp fallback; descriptors and logs remain
workspace-local under `KAST_CACHE_HOME`.

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

## Local feedback loop

Run these checks before changing release workflows or Devin blueprint inputs.
They build fixture artifacts locally and exercise the installer failure paths
without uploading anything.

```bash
npm --prefix setup-kast ci
npm --prefix setup-kast test
.github/scripts/test-devin-artifact-packagers.sh
.github/scripts/test-setup-kast-action.sh
scripts/verify-setup-kast-install.sh \
  --skip-daemon \
  --allow-missing-gradle-cache \
  --install-dir "$KAST_HOME"
```

The setup contract covers successful install, transient HTTP retry, runtime
checksum mismatch, unsafe archive members, unsafe symbolic links, missing
manifest, manifest schema mismatch, unsupported manifest fields, unsupported
archive member types such as hardlinks, unsupported architecture, invalid
multiline inputs, optional cache miss, strict cache miss, non-strict
`kast doctor` behavior, read-only Gradle cache permissions, reinstall over an
existing read-only cache, and sudo fallback for `/opt`-style install roots.

Run the heavier real-artifact smoke when you need local confidence in the
current repo outputs rather than only fixture artifacts:

```bash
.github/scripts/test-setup-kast-real-artifacts.sh
```

That command builds the host-compatible CLI and headless backend, packages the
runtime, installs it through `setup-kast`, starts the headless backend on a tiny
workspace through `scripts/verify-setup-kast-install.sh`, and checks
daemon-state isolation. On non-Linux developer machines it uses a
host-compatible CLI binary so the action can run locally; the CI job below is
the Linux artifact proof. Set `KAST_SETUP_KAST_SMOKE_BUILD=false` only when you
intentionally want to reuse existing local build outputs. The smoke also runs a
repo-level Gradle warm check by default through
`scripts/verify-setup-kast-install.sh --gradle-root "$PWD"`: `./gradlew
--version`, `./gradlew dependencies`, and `./gradlew buildEnvironment` run with
`GRADLE_RO_DEP_CACHE` pointed at the installed read-only cache and
`GRADLE_USER_HOME` pointed at the writable session cache. Set
`KAST_SETUP_KAST_SMOKE_GRADLE_WARM=false` only when diagnosing a narrower
runtime issue.

CI also has a `setup-kast runtime artifact` job that consumes the real Linux CLI
and headless backend artifacts from earlier CI jobs, packages the Devin runtime,
invokes `setup-kast` as a local GitHub Action, runs
`scripts/verify-setup-kast-install.sh`, starts the installed headless backend on
a tiny Kotlin workspace, and verifies that daemon state stays out of the install
tree. The same verifier call passes `--gradle-root "$GITHUB_WORKSPACE"` so the
job also proves a repo-level Gradle warm step after setup, without rebuilding
the Kast runtime. Treat that job as the pre-release proof for the real artifact
path; the local fixture tests are the fast regression loop for edge cases.

## Devin snapshot proof

The GitHub CI loop proves the action/runtime contract before publication, but a
real Devin snapshot is still an external async build. Devin's
[GitHub Actions blueprint support](https://docs.devin.ai/onboard-devin/environment/github-actions)
is the reason this contract uses a Node action subpath, and Devin's
[snapshot build API](https://docs.devin.ai/api-reference/v3/snapshot-setup/post-organizations-builds)
is the final build-status boundary for the snapshot itself.

Use `scripts/verify-devin-snapshot-build.sh` when a service-user credential is
available. The token must be supplied through `DEVIN_SERVICE_USER_TOKEN`, with
`DEVIN_API_TOKEN` accepted as a fallback, so it does not appear in shell history
or process listings. Triggering a build requires `ManageOrgSnapshots`; polling
build status requires `ManageRepoBlueprints`.

```bash
DEVIN_SERVICE_USER_TOKEN=cog_... \
  scripts/verify-devin-snapshot-build.sh \
    --org-id <org-id> \
    --trigger
```

For a build that was already started through the Devin UI or another automation
path, poll the existing build id instead of triggering a new one.

```bash
DEVIN_SERVICE_USER_TOKEN=cog_... \
  scripts/verify-devin-snapshot-build.sh \
    --org-id <org-id> \
    --build-id <build-id>
```

The local contract test for that script uses a fake Devin API server and covers
dry-run behavior, missing credentials, trigger-and-poll success, terminal
failure, and token-safe logging:

```bash
.github/scripts/test-devin-snapshot-build-verifier.sh
```

This proves the snapshot build reaches Devin's `succeeded` status. It does not
replace the Phase 1 final acceptance commands inside a fresh Devin session,
because only that session can prove the installed `kast`, Gradle cache, and
workspace-local daemon state from the booted snapshot.
