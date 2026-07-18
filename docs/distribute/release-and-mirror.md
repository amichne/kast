---
title: Release And Mirror Workflow
description: Build, verify, mirror, and activate Kast release artifacts.
icon: lucide/package-check
---

# Release And Mirror Workflow

Use this guide when you are building, promoting, mirroring, or image-layering
Kast artifacts. Most macOS developers should use the [macOS install
guide](../install/macos.md) instead.

## Publish The IDEA Plugin

The release workflow builds one unsigned `kast-idea-<tag>.zip` and renders one
`updatePlugins.xml` whose URL names that exact ZIP. Both are ordinary GitHub
Release assets. There is no JetBrains Marketplace publication, signing,
certificate enrollment, Pages repository, IDEA provenance entry, or plugin
checksum requirement.

Mirror both files together if an internal JetBrains custom repository should
serve the plugin. The public stable feed is
`https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml`.

## Package The Codex Plugin

The Codex marketplace is generated from the release-built Rust command
contract. Check committed generated assets before creating the release staging
tree:

```console title="Check Codex plugin generation"
kast developer codex generate --check
```

Release generation uses the binary's `KAST_VERSION` and writes into an
isolated staging directory. It does not accept a caller-provided version.

```console title="Generate the release marketplace"
kast developer codex generate \
  --release \
  --output-dir "$PWD/build/codex-plugin-release"
```

The `build-codex-plugin` producer packages
`kast-codex-plugin-<tag>.zip`, where the tag includes its leading `v`. The
archive root contains `marketplace.json` and `plugins/kast/`; it contains no
Kast binary, MCP server, app connector, custom agent profile, raw RPC payload,
or copied command catalog.

Validate the archive before publication:

```console title="Verify the Codex plugin archive"
export KAST_RELEASE_TAG="v1.2.3"
.github/scripts/verify-codex-plugin-package.py \
  --archive "dist/kast-codex-plugin-${KAST_RELEASE_TAG}.zip" \
  --version "${KAST_RELEASE_TAG#v}"
```

The producer records `build-ledger-codex-plugin.json` with artifact kind
`release-codex-plugin` and provenance platform `codex-plugin`. The immutable
uploader publishes the ZIP before aggregate checksums are generated. Release
verification must prove one release version across the CLI, Codex manifest,
generated exposure asset, build ledger, and provenance.
`scripts/verify-release-assets.sh` reapplies the package validator to the
downloaded archive and binds its digest, version, and generator identity to the
Codex provenance record.

## Package A Linux Headless Bundle

The Ubuntu/Debian bundle combines the Rust CLI and portable headless backend
runtime into one server artifact.

```console title="Package a bundle"
kast developer release package ubuntu-debian-bundle \
  --cli-archive dist/kast-<version>-linux-x64.zip \
  --backend-archive backend-headless/build/distributions/backend-headless-<version>-portable.zip \
  --version <version> \
  --bundle-output dist/kast-ubuntu-debian-headless-x86_64-<version>.tar.gz
```

Publish the `.tar.gz` and matching `.sha256` sidecar together. Do not promote a
bundle without its checksum and release receipts.

## Verify A Release Directory

Mirror or promote release assets as one unit, then verify the downloaded
directory before importing it into an internal store.

```console title="Verify a downloaded release"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

Use the bundle smoke before treating a locally built server artifact as
installable.

```console title="Smoke a Linux server bundle"
./scripts/smoke-ubuntu-debian-bundle.sh
```

## Activate Or Verify A Bundle

Image builds and mirror workflows can activate an extracted or archived bundle
into a managed install root.

```console title="Activate a bundle"
kast developer release activate bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz
```

Use `--verify-only` before publishing an image layer or promoting a mirrored
bundle.

```console title="Verify without mutation"
kast developer release activate bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz \
  --verify-only
```

## Install From A Mirror

The Ubuntu/Debian installer can download from a mirrored release directory or
use an exact local tarball.

```console title="Install from a mirrored release directory"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_BASE_URL="https://artifacts.example.com/kast"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

```console title="Install from a local artifact"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

The runtime artifact contract defines bundle names, manifest fields, checksum
requirements, hosted-agent cache layout, and release ledger expectations.
Continue with [runtime artifact contract](runtime-artifact-contract.md) when a
mirror or image build needs exact facts.
