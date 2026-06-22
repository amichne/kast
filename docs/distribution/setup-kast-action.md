---
title: setup-kast action
description: Install the prebuilt Linux x64 Kast runtime in Devin blueprints
  and other ephemeral workspaces.
icon: lucide/workflow
---

# setup-kast Action

Use `setup-kast` when a Linux x64 blueprint or CI job already has Kast runtime
artifacts and needs an isolated headless install. The action consumes release
artifacts, validates them, writes the Kast install manifest, and puts the installed
`kast` binary on later action steps' `PATH`.

!!! warning "Not a first-time developer install"
    This is not the macOS developer-machine path and it is not the
    Ubuntu/Debian server bundle. Use it for hosted Linux workspaces, Devin
    snapshots, and CI proof that need `/opt/kast/current` style setup from
    immutable artifacts.

## Choose the right installer

Kast has three install surfaces. Pick the one that matches the machine and the
reader's job before copying commands.

- **macOS developer machine:** use the
  [developer machine install](../getting-started/install.md) when a human runs
  IDEA or Android Studio locally.
- **Linux server or image:** use the
  [headless Linux server](../getting-started/headless-linux.md) path when a
  persistent host should install from the Ubuntu/Debian bundle.
- **Ephemeral Linux x64 workspace:** use this `setup-kast` action when a
  blueprint or CI job already has runtime artifacts and optional Gradle cache
  artifacts.

## Required runtime inputs

The action installs one Linux x64 runtime version at a time. Runtime artifacts
come from release output, mirrored release storage, or local CI packaging.

| Input | Required | Purpose |
|-------|----------|---------|
| `version` | yes | Semver path segment for the managed install directory, such as `1.2.3` or `v1.2.3` |
| `artifact-url` | yes | `file://`, absolute path, HTTP, or HTTPS location for `kast-headless-linux-x64.tar.zst` |
| `artifact-sha256` | yes | SHA-256 digest for the runtime archive |
| `manifest-url` | no | Sidecar `kast-runtime-manifest.json`; omit only when the archive already contains the manifest |
| `install-dir` | no | Managed install root, defaulting to `/opt/kast` |
| `strict` | no | Defaults to `true`; when true, `kast doctor` must pass before publish |

The action rejects non-Linux x64 runners, unsafe `version` values, bad
checksums, unsupported manifest fields, manifest/archive digest mismatches, and
runtime archives that do not contain the expected `bin/kast`, `lib`, and `idea`
layout.
The `version` must be a semver path segment because it becomes part of the
managed install path.

## Optional Gradle cache inputs

Use the Gradle read-only cache when a snapshot should boot with dependencies
already available. The cache is installed separately from the Kast runtime,
even when both artifacts come from the same release tag.

| Input | Required | Purpose |
|-------|----------|---------|
| `gradle-ro-cache-url` | no | `file://`, absolute path, HTTP, or HTTPS location for `gradle-ro-dep-cache.tar.zst` |
| `gradle-ro-cache-sha256` | no | Optional SHA-256 digest for the Gradle cache artifact |
| `fail-on-cache-miss` | no | Defaults to `false`; set to `true` when cache installation is part of acceptance |

The cache archive must contain `gradle-ro/modules-2` at the archive root. The
action rejects lock files, Gradle GC metadata, unsafe archive paths, symbolic
links, and unsupported archive member types before publishing the cache.

## Public release assets

For published Kast releases, use the public GitHub release assets directly.
These URLs do not require an ACloud token, authorization header, signed URL, or
other artifact-store credential.

```text
https://github.com/amichne/kast/releases/download/v1.0.0/kast-headless-linux-x64.tar.zst
https://github.com/amichne/kast/releases/download/v1.0.0/kast-runtime-manifest.json
https://github.com/amichne/kast/releases/download/v1.0.0/gradle-ro-dep-cache.tar.zst
https://github.com/amichne/kast/releases/download/v1.0.0/gradle-ro-dep-cache.sha256
https://github.com/amichne/kast/releases/download/v1.0.0/SHA256SUMS
```

Read `artifact-sha256` and `gradle-ro-cache-sha256` from `SHA256SUMS` or the
matching `.sha256` sidecars for the same tag.

## Private artifacts and retries

HTTP artifacts are streamed to disk and checksummed from disk. That keeps large
runtime archives out of Node.js memory and gives checksum failures a single
clear boundary.
Public GitHub release assets do not need the authorization inputs below; use
them only for private artifact stores or mirrors.

| Input | Purpose |
|-------|---------|
| `authorization-header` | Default HTTP `Authorization` header for all downloads |
| `artifact-authorization-header` | Runtime archive header override |
| `manifest-authorization-header` | Manifest header override |
| `gradle-ro-cache-authorization-header` | Gradle cache header override |
| `download-attempts` | Retry count, default `3`, allowed range `1..10` |
| `download-retry-delay-ms` | Delay between attempts, default `1000` |
| `download-timeout-ms` | Per-attempt HTTP timeout, default `120000` |

Prefer header-based credentials when the artifact store supports them. The
action never prints full HTTP artifact URLs. Keep signed URLs short-lived;
retry and failure messages stay secret-safe.

## Action reference

The setup action is published from the
[`amichne/kast-action`](https://github.com/amichne/kast-action) repository and
listed for normal GitHub Actions resolution as `amichne/kast-action@v1`. Devin
blueprints use the URL form `github.com/amichne/kast-action@v1`.
The action ref pins the installer implementation; the `version`,
`artifact-url`, `artifact-sha256`, and `manifest-url` inputs pin the Kast
runtime artifacts that the installer consumes.

The action requires `tar` and `zstd` on `PATH`; install `zstd` before invoking
the action when the runner image does not already provide it.

In GitHub Actions, call the Marketplace action directly.

```yaml
steps:
  - uses: amichne/kast-action@v1
    with:
      version: "1.0.0"
      artifact-url: file://${{ github.workspace }}/dist/kast-headless-linux-x64.tar.zst
      artifact-sha256: ${{ steps.package-runtime.outputs.runtime_sha }}
      manifest-url: file://${{ github.workspace }}/dist/kast-runtime-manifest.json
      install-dir: ${{ runner.temp }}/kast-install
      strict: "true"
```

## Devin blueprint step

Use the same published action through the Devin action URL form when a
blueprint or mirrored marketplace needs a resolvable action reference.

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
    uses: github.com/amichne/kast-action@v1
    with:
      version: "1.0.0"
      artifact-url: "https://github.com/amichne/kast/releases/download/v1.0.0/kast-headless-linux-x64.tar.zst"
      artifact-sha256: "$KAST_HEADLESS_SHA256"
      manifest-url: "https://github.com/amichne/kast/releases/download/v1.0.0/kast-runtime-manifest.json"
      gradle-ro-cache-url: "https://github.com/amichne/kast/releases/download/v1.0.0/gradle-ro-dep-cache.tar.zst"
      gradle-ro-cache-sha256: "$KAST_GRADLE_RO_CACHE_SHA256"
      fail-on-cache-miss: "true"
      install-dir: "/opt/kast"

  - name: Verify and persist Kast environment
    run: |
      export KAST_INSTALL_ROOT=/opt/kast
      export PATH=/opt/kast/current/bin:$PATH
      export KAST_CACHE_HOME=$HOME/.cache/kast
      export KAST_CONFIG_HOME=$HOME/.config/kast
      export GRADLE_RO_DEP_CACHE=/opt/kast/cache/gradle-ro
      export GRADLE_USER_HOME=$HOME/.gradle

      {
        echo 'export KAST_INSTALL_ROOT=/opt/kast'
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
      test -f /opt/kast/install.json
      test -f /opt/kast/current/kast-runtime-manifest.json
      test -d /opt/kast/cache/gradle-ro/modules-2
```

The action itself writes `KAST_INSTALL_ROOT`, `KAST_CACHE_HOME`,
`KAST_CONFIG_HOME`, and the installed `bin` directory through `GITHUB_ENV` and
`GITHUB_PATH`.
Persist shell exports separately when the booted workspace session needs them
outside the action runner process.

## Installed layout

By default the action installs a versioned runtime under `/opt/kast`, then
publishes the `current` symlink only after the archive, runtime manifest,
install manifest, and `kast doctor` checks pass.

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

The generated `install.json` lives under `KAST_INSTALL_ROOT`. It records the
active version, stable entrypoint, active binary, backend runtime paths, and
state/cache/log roots. `config.toml` under `KAST_CONFIG_HOME` is optional and
behavior-only; it must not point at runtime libraries, IDEA home, daemon
descriptors, sockets, logs, or the CLI binary.

## Verify the install

Every blueprint should prove that the `kast` on `PATH` is the action-managed
binary and that daemon state is not written into the immutable install tree.

```bash
scripts/verify-setup-kast-install.sh \
  --install-dir /opt/kast/current \
  --workspace-id setup-kast-smoke \
  --module-name setup-kast-smoke \
  --gradle-root "$GITHUB_WORKSPACE"
```

Pass `--allow-missing-gradle-cache` when a scenario intentionally omits the
read-only cache, or `--skip-daemon` when checking install shape without
starting the headless backend. Keep `/opt/kast/current/bin` ahead of any global
Kast install so the verifier does not catch a stale machine-level binary.
The `--gradle-root` option runs a repo-level Gradle warm check against the
installed read-only cache and writable session cache.

## Local feedback loop

Run the action loop in the `amichne/kast-action` repository before changing
action inputs or installer behavior. Run the Kast artifact loop in this
repository before changing runtime artifact layout or Devin blueprint wiring.

```bash
cd ../kast-action
npm ci
npm run lint
npm test
npm run test:fixtures

cd ../kast
.github/scripts/test-devin-artifact-packagers.sh
```

The fixture action test covers successful install, download retry, checksum
mismatch, unsafe archives, missing or invalid manifests, unsupported platform,
optional and strict cache misses, non-strict `kast doctor`, read-only Gradle
cache permissions, reinstall over an existing cache, and sudo fallback for
`/opt`-style roots.

## Snapshot proof

GitHub CI proves the action/runtime contract before publication, but a real
Devin snapshot is still an external async build. Use the snapshot verifier only
when a service-user credential is available.

```bash
DEVIN_SERVICE_USER_TOKEN=cog_... \
  scripts/verify-devin-snapshot-build.sh \
    --org-id <org-id> \
    --trigger
```

Poll an existing build instead of triggering a new one when the build started
through the Devin UI or another automation path. The token must come from
`DEVIN_SERVICE_USER_TOKEN`, with `DEVIN_API_TOKEN` accepted as a fallback, so it
does not appear in shell history or process listings.
Triggering a snapshot build requires `ManageOrgSnapshots`; polling build status
requires `ManageRepoBlueprints`.

## Related pages

Use the adjacent pages when the work is about artifact shape or a different
Linux install path.

- [Runtime artifact contract](runtime-artifact-contract.md) defines the files,
  manifest schema, and Gradle cache layout that `setup-kast` validates.
- [Headless Linux server](../getting-started/headless-linux.md) covers the
  Ubuntu/Debian bundle for persistent Linux hosts and images.
