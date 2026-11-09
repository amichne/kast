---
type: How To
title: "Prepare an OpenAI cloud image with Kast"
description: "Install an exact qualified Linux bundle during image setup and verify its receipt before task activation."
resource: "repo://packaging/cloud/openai-image"
tags: [kast, cloud, linux, installation]
timestamp: "2026-11-09T00:00:00Z"
source_revision: a54a5b04ceee306f520ac20f87f2f4a608c1f5da
code_sources:
  - path: packaging/cloud/openai-image.py
  - path: packaging/test-openai-image.py
  - path: packaging/composed_bundle.py
  - path: packaging/managed_headless.py
  - path: .github/workflows/composed-linux.yml
---

# Prepare an OpenAI cloud image with Kast

Build the native package in CI, then install its approved bytes during image setup.
Task startup verifies the existing installation and restores its command path.
It performs no download, build, upgrade, or Host startup.
The environment owner still needs to test and publish the saved OpenAI environment configuration.
Changes inside a running task do not publish a new image.

This guide prepares the setup commands. A complete image qualification still needs a successful
Linux bundle build and an installation in the target image.
No bundle digest or deployment result is assumed here.

## Choose the exact CI output

Use a successful **Composed Linux bundle** run for the reviewed source revision.
Download `kast-composed-linux-SHA-RUN_ID-ATTEMPT` and inspect `assembly.json` and `qualification.json`.
Both records must identify the same setup archive digest.
The qualification record must report `COMPLETE` for the native probe.
Keep the source revision, run, attempt, version, archive digest, and qualification record with the image configuration.
Later workflow jobs can consume `setup_artifact_id` and `setup_archive_sha256` directly.

GitHub keeps these artifacts for 30 days.
For routine rebuilds, keep the approved archive in a durable artifact location and pin its SHA-256 digest.
Do not select a moving `latest` URL during task startup.
An authenticated archive download belongs in image setup, using the environment's credential settings.
Keep credentials out of archives, activation scripts, and image receipts.

## Image install command

Run as the ordinary environment user on Linux x86-64 with Python 3.12 or 3.13 and uv 0.12.9 or newer.
The composed archive supplies the selected Host runtime and bundled JBR.
Image setup needs no GraalVM, C compiler, full IDE download, or source build.
Use the environment's normal system-package installer if the target image lacks required runtime libraries.

Set these non-secret values in the reviewed setup configuration:

- `KAST_BUNDLE_ARCHIVE`: the local path to the downloaded setup `.tar.gz`.
- `KAST_BUNDLE_SHA256`: the approved digest of that archive, not the outer GitHub artifact ZIP.
- `KAST_BUNDLE_VERSION`: the exact version recorded by the producer.

From the prepared Kast checkout, use this install command:

```sh
set -eu
: "${KAST_BUNDLE_ARCHIVE:?Select a downloaded qualified archive}"
: "${KAST_BUNDLE_SHA256:?Pin its approved SHA-256 digest}"
: "${KAST_BUNDLE_VERSION:?Pin its exact version}"
uv run --locked --project packaging python packaging/cloud/openai-image.py install \
  --archive "$KAST_BUNDLE_ARCHIVE" --sha256 "$KAST_BUNDLE_SHA256" \
  --version "$KAST_BUNDLE_VERSION" --root /workspace/kr
```

The helper delegates archive admission and installation to the existing bundle installer.
It grants no workspace trust.
Repeating setup reuses a matching installation after a receipt and version check, without reading the archive.
A different digest, incomplete installation, or failed version command stops setup.
It never replaces an existing root. Select a new short root when changing the bundle pin.
Keep the previous root until the replacement image passes validation.

## Task activation

Restore the same version and digest settings in each task.
Run from the prepared checkout, or use absolute paths to its helper and packaging project:

```sh
set -eu
kast_exports="$(uv run --locked --project packaging python packaging/cloud/openai-image.py activate \
  --sha256 "$KAST_BUNDLE_SHA256" --version "$KAST_BUNDLE_VERSION" --root /workspace/kr)"
eval "$kast_exports"
```

Check the assignment's exit status before evaluating exports; `set -e` does that here.
Activation adds the wrapper directory to `PATH`.
It preserves the current task's proxy and CA settings, `HOME`, and `JAVA_HOME`.
The helper starts no Host and accepts no project trust.
Installation and verification JSON report `semanticReadiness: NOT_RUN`.
Receipt and version checks prove no compiler query.

The wrapper uses the bundle's private Host state.
Project dependency downloads still need the current task's approved network access and Java trust configuration.
Validate those connections after task resume; do not bake a proxy address or credential into the image.
The existing development toolchain activation can continue to configure Gradle for source builds separately.

## Slopsentral and image validation

Install the Slopsentral command through its supported installer during image setup.
Pin the selected command release and install the selected Codex plugins with its native commands.
Record `codex plugin list --json` with the image evidence.
The native marketplace channel can change independently of the command version.
Review and record the resolved plugin versions when rebuilding the image.
Use the prepared plugins at task activation without refreshing the marketplace.
Restart Codex after changing installed plugins.

Before publishing, test a fresh setup, repeated setup, and a resumed task with its current proxy settings.
The [Linux producer guide](../../docs/composed-linux-release.md) describes the native compiler-query probe.
Run a compiler query in an explicitly trusted disposable repository inside the target image as separate evidence.
Check that installation, cache, and plugin state belong to the intended image user.
Then save and publish the environment through its settings flow.
Keep the artifact pin with that published configuration so a later rebuild can select the same bytes.
