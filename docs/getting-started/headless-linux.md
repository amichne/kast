---
title: Headless Linux server
description: Install the Linux headless bundle for CI runners, hosted agents,
  and server images.
icon: lucide/server
---

# Headless Linux Server

Use this path for CI runners, hosted agents, server images, or air-gapped
Linux hosts that should not depend on Homebrew or an open developer IDE. This
is materially different from the macOS developer-machine install: it installs a
server-local `kast` binary plus the packaged headless runtime.

Most developers do not need this page. For local macOS development, use the
[developer machine install](install.md) instead.

## Install the bundle

The Ubuntu/Debian bundle installs the binary, install manifest, and backend
runtime together.

```bash title="Install Kast on Ubuntu or Debian"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
kast developer runtime up --backend=headless
```

The release asset is
`kast-ubuntu-debian-headless-x86_64-<version>.tar.gz` with a matching
`.sha256` sidecar. The bundle contains the Rust CLI, one backend portable
runtime, `scripts/install-ubuntu-debian.sh`, metadata, and the license notice.

??? info "Server install details"
    The installer refuses non-Ubuntu/Debian hosts, installs to
    `$HOME/.local/share/kast/versions/<version>` by default, flips
    `$HOME/.local/share/kast/current`, symlinks `$HOME/.local/bin/kast`, and
    writes `$HOME/.local/share/kast/install.json` so the CLI resolves the
    bundled headless runtime from one manifest-backed path model.

    Java 21 or newer must be available on `PATH` or through `JAVA_HOME` when
    the Linux headless runtime starts.

## Mirrors and image builds

Point the installer at an exact local tarball when the server pulls from a
private artifact store or baked image layer.

```bash title="Install from a mirrored Linux headless tarball"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

??? question "Ubuntu/Debian installer overrides"
    Most installs do not need environment overrides. Use them only for
    packaged images, private artifact stores, and CI setup scripts.

    | Variable | What it does |
    |----------|--------------|
    | `KAST_UBUNTU_DEBIAN_VERSION` | Selects the release tag to install |
    | `KAST_UBUNTU_DEBIAN_ARTIFACT_PATH` | Installs from an exact local bundle tarball |
    | `KAST_UBUNTU_DEBIAN_BASE_URL` | Downloads from a mirrored release directory |
    | `KAST_UBUNTU_DEBIAN_ROOT` | Overrides the managed install root |
    | `KAST_UBUNTU_DEBIAN_BIN_DIR` | Overrides the `kast` symlink directory |
    | `KAST_UBUNTU_DEBIAN_CONFIG_HOME` | Overrides the config directory |
    | `KAST_JAVA_CMD` | Selects the Java executable used for verification |

## Release asset verification

Published releases include CLI zips, the IDEA plugin zip, the Linux headless
tarball with its `.sha256` sidecar, `SHA256SUMS`, and
`build-provenance.json`. Mirror or promote the release directory as a unit,
then run the same verifier used by CI before importing Kast artifacts into an
internal store.

```bash title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

Use the Rust packager when building the release bundle from local CLI and
backend artifacts:

```bash title="Package a local Ubuntu/Debian bundle"
kast developer release package ubuntu-debian-bundle \
  --cli-archive dist/kast-<version>-linux-x64.zip \
  --backend-archive backend-headless/build/distributions/backend-headless-<version>-portable.zip \
  --version <version> \
  --bundle-output dist/kast-ubuntu-debian-headless-x86_64-<version>.tar.gz
```

## Hosted agents

Short-lived Linux x64 workspaces can use the `kast-action` repository instead
of running the Ubuntu/Debian installer directly. That path installs `kast`
under `/opt/kast/current`, activates an install manifest, and seeds an optional
read-only Gradle dependency cache.

The [runtime artifact contract](../distribution/runtime-artifact-contract.md)
defines the artifact names, manifest schema, cache layout, and
`kast-action@v2` compatibility smoke. Detailed action inputs and enterprise
mirror guidance live in the sibling `kast-action` repository.

## Next steps

After installing the bundle, use the command manual and troubleshooting pages
for runtime behavior.

- [Quickstart](quickstart.md) walks through a first headless analysis session.
- [Commands](../commands/index.md) explains lifecycle, install, agent, metrics,
  and LSP commands.
- [Troubleshooting](../troubleshooting.md) covers startup and indexing issues.
