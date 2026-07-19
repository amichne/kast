---
title: Runtime Artifact Contract
description: Reference for Linux bundle, runtime manifest, checksum, and hosted-agent artifact contracts.
icon: lucide/file-check-2
---

# Runtime Artifact Contract

This page is the reference contract for release artifacts used by Linux
servers, hosted agents, mirrors, and image layers. It records artifact names,
manifest shape, validation receipts, and install failure boundaries.

## Ubuntu/Debian Bundle

The public Linux headless asset name is:

```text
kast-ubuntu-debian-headless-x86_64-<version>.tar.gz
```

The bundle must have a matching checksum sidecar:

```text
kast-ubuntu-debian-headless-x86_64-<version>.tar.gz.sha256
```

The bundle contains the Rust CLI, one portable headless backend runtime,
`scripts/install-ubuntu-debian.sh`, metadata, and the license notice.

## Installer Manifest

The Ubuntu/Debian installer writes the active install manifest under the
configured Kast data root. The default managed layout is:

| Path | Role |
| --- | --- |
| `$HOME/.local/share/kast/versions/<version>` | Versioned install root |
| `$HOME/.local/share/kast/current` | Active install pointer |
| `$HOME/.local/bin/kast` | User-facing binary symlink |
| `$HOME/.local/share/kast/install.json` | Manifest-backed active install state |

Install clients must fail before trusting an artifact when the manifest is
missing, malformed, points outside the managed root, or disagrees with the
selected bundle.

## Runtime Manifest Schema

Runtime artifacts include a manifest that describes version, platform, Java
runtime, backend runtime, source-index schema, and artifact digest. The JSON
schema lives beside this page:
[kast-runtime-manifest.schema.json](kast-runtime-manifest.schema.json).

Required manifest fields are:

| Field | Contract |
| --- | --- |
| `schemaVersion` | Integer schema version, currently `1` |
| `kastVersion` | Non-empty Kast version |
| `kastGitSha` | Git SHA, 7 to 40 lowercase hex characters |
| `os` | `linux` |
| `arch` | `x64` |
| `javaVersion` | Numeric Java major version |
| `intellijBuild` | Non-empty IntelliJ platform build string |
| `kotlinPluginVersion` | Non-empty Kotlin plugin version |
| `kastIndexSchemaVersion` | Numeric source-index schema version |
| `artifactSha256` | Lowercase 64-character SHA-256 digest |

Missing fields, unsupported platforms, unsupported schema versions, and digest
mismatches are install failures.

## CI Runtime Input Archive

Pull-request Linux artifacts originate from one immutable CI-only archive:

```text
kast-local-prepared-generation.tar.zst
```

Its unpacked root contains the captured source snapshot, exact executable CLI,
and headless backend. The producing jobs validate and ledger those components
before assembly. This archive is packaging input; it is never activated as a
developer-machine authority.

CI has one producer for this runtime input and one focused producer for each
derived package family. The Ubuntu/Debian bundle is produced independently of
the headless runtime archive, runtime manifest, and Gradle read-only cache, so
neither package family delays the other. Container and action jobs download and
verify their ledgered files; they do not rebuild the CLI or backend and do not
repackage the downloads.

The source-bound Linux backend is the sole pull-request portable-backend
producer. Its archive, no-fat-jar layout assertion, and ledger replace the
retired macOS portable proofs, whose artifact had no consumer or release path.
macOS release authority is the separate GitHub-hosted IDEA plugin.

## Build Ledger

Release, snapshot, and pull-request generation automation builds each
publishable artifact once for a commit, then records a CI artifact ledger with
artifact kind, producer job, build command, source SHA, and SHA-256 digest.

Packaging and publication jobs must verify the ledger against the exact
downloaded file before using it.

```console title="Verify a CI artifact ledger"
scripts/verify-ci-artifact-ledger.py verify \
  --ledger dist/build-ledger-cli-linux-x64.json \
  --git-sha <commit-sha> \
  --require-kind release-cli-linux-x64 \
  --artifact release-cli-linux-x64=dist/kast-<version>-linux-x64.zip
```

## Hosted Agent Compatibility

Short-lived Linux x64 workspaces can use the `kast-action` repository. The
action installs `kast` under `/opt/kast/current`, activates an install
manifest, and can seed a read-only Gradle dependency cache.

`kast-action@v2` compatibility requires the same bundle, manifest, checksum,
and Java/runtime assumptions as the Ubuntu/Debian installer. Detailed action
inputs and enterprise mirror guidance live in the sibling action repository.
