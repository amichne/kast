---
title: Distribution
description: Build, verify, and activate Kast distribution artifacts.
icon: lucide/package-check
---

# Distribution

Distribution commands exist for releases, mirrors, server images, and hosted
agent snapshots. Most macOS developers only need `brew install kast`, the
IntelliJ plugin activation path, and repository open-time setup; use this page
when you are building or promoting artifacts.

## Ubuntu/Debian bundle

The Linux headless bundle packages the Rust CLI and backend runtime into one
tarball. Release automation builds it from the CLI archive and portable
headless backend archive.

```console title="Package a Linux headless bundle"
kast developer release package ubuntu-debian-bundle \
  --cli-archive dist/kast-<version>-linux-x64.zip \
  --backend-archive backend-headless/build/distributions/backend-headless-<version>-portable.zip \
  --version <version> \
  --bundle-output dist/kast-ubuntu-debian-headless-x86_64-<version>.tar.gz
```

The public release asset name is
`kast-ubuntu-debian-headless-x86_64-<version>.tar.gz` with a matching
`.sha256` sidecar.

## Activate a bundle

Use `kast developer release activate bundle` when an image build or mirror workflow has
an extracted or archived bundle and should activate it into a managed install
root.

```console title="Activate or verify a bundle"
kast developer release activate bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz

kast developer release activate bundle \
  --source /artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz \
  --verify-only
```

`--verify-only` validates the bundle and current install without changing
files. Use it before publishing an image layer or promoting a mirrored bundle.

## Server installer

The Ubuntu/Debian installer is the bootstrap entrypoint for servers and hosted
Linux agents. It installs the selected bundle, writes the install manifest, and
verifies the active binary.

```console title="Install from a release or mirror"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

Use `KAST_UBUNTU_DEBIAN_ARTIFACT_PATH` when the bundle already exists in an
image layer or private artifact store.

```console title="Install from a local artifact"
export KAST_UBUNTU_DEBIAN_VERSION="v1.2.3"
export KAST_UBUNTU_DEBIAN_ARTIFACT_PATH="/artifacts/kast-ubuntu-debian-headless-x86_64-v1.2.3.tar.gz"
./scripts/install-ubuntu-debian.sh install
./scripts/install-ubuntu-debian.sh verify
```

## Release verification

Mirror or promote release assets as one unit. The release verifier checks asset
names, checksums, provenance, and installable bundle shape.

```console title="Verify a downloaded release directory"
gh release download v1.2.3 --repo amichne/kast --dir kast-release-v1.2.3
./scripts/verify-release-assets.sh --release-dir kast-release-v1.2.3 --tag v1.2.3
```

Use the bundle smoke before treating a locally built server artifact as
installable.

```console title="Smoke the Linux server bundle"
./scripts/smoke-ubuntu-debian-bundle.sh
```

## Build receipts

Release and snapshot automation builds each publishable artifact once for a
commit, then records a CI artifact ledger with the artifact kind, producer job,
build command, source SHA, and SHA-256 digest. Packaging and publication jobs
must verify the ledger against the exact downloaded file before using it.

Use the ledger verifier for local checks or workflow contract fixtures:

```console title="Verify a CI artifact ledger"
scripts/verify-ci-artifact-ledger.py verify \
  --ledger dist/build-ledger-cli-linux-x64.json \
  --git-sha <commit-sha> \
  --require-kind release-cli-linux-x64 \
  --artifact release-cli-linux-x64=dist/kast-<version>-linux-x64.zip
```

## Runtime manifest

Bundle and hosted-agent runtime artifacts include a manifest that describes the
version, platform, Java runtime, backend runtime, and artifact digest. The
schema lives beside this page for release tooling:
[`kast-runtime-manifest.schema.json`](kast-runtime-manifest.schema.json).

Any setup client must validate the manifest and checksum before trusting an
artifact. Missing fields, unsupported platforms, unsupported schema versions,
and digest mismatches are install failures.
