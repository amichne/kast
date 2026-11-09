---
type: How To
title: "Prepare an OpenAI cloud image with Kast"
description: "Install an exact qualified Linux bundle during image setup and verify its receipt before task activation."
resource: "repo://packaging/cloud/openai-image"
tags: [kast, cloud, linux, installation]
timestamp: "2026-11-09T00:00:00Z"
source_revision: ed105da510774b5195505cfd24edb2e4a28590ae
code_sources:
  - path: packaging/cloud/openai-image.py
  - path: packaging/cloud/openai-kast.env
  - path: packaging/cloud/setup-openai-image.sh
  - path: packaging/cloud/activate-openai-image.sh
  - path: packaging/cloud/prepare-openai-workspace.sh
  - path: packaging/test-openai-image.py
  - path: packaging/composed_bundle.py
  - path: packaging/managed_headless.py
  - path: .github/workflows/composed-linux.yml
  - path: .github/workflows/cloud-image-transfer.yml
---

# Prepare an OpenAI cloud image with Kast

Build the native package in CI, then install its approved bytes during image setup.
Task startup verifies the existing installation and restores its command path.
It performs no download, build, upgrade, or Host startup.
The environment owner still needs to test and publish the saved OpenAI environment configuration.
Changes inside a running task do not publish a new image.

The preset was validated in the selected cloud container.
A newly saved image still needs fresh setup and resumed-task tests before publication.
The preset below pins a qualified build. Creating the saved environment still requires the environment settings flow.

## Qualified preset

Use **Kast Linux + Slopsentral** as the new environment name.
The checked-in [pin file](openai-kast.env) selects this input:

| Setting | Value |
| --- | --- |
| Native package version | `0.0.0` |
| Source revision | `fdf7c6d55662bf8dc162cb120b7464fd6757bafc` |
| Qualified build | [42543736511, attempt 1](https://github.com/amichne/kast/actions/runs/42543736511) |
| Setup artifact | `13642593952` |
| Setup SHA-256 | `aa001a8c56497473644978f2e585549e8d3f09d9e12d04c1404b89da26f543ec` |
| Slopsentral command | `4.0.12` |
| Prepared runtime | `/workspace/kc` |

From a reviewed checkout, install the prepared preset with:

```sh
bash packaging/cloud/setup-openai-image.sh /path/to/kast-composed-setup-v0.0.0-linux-x86_64.tar.gz
```

Setup installs the native package and five selected Slopsentral plugins.
It copies committed public setup code into the runtime profile and prepares the checkout's Gradle wrapper with `--version`.
That command downloads the wrapper distribution through the current proxy without building the project.
The copied profile lets task activation work from any directory after the source checkout changes.

In each task shell, load the prepared profile:

```sh
set -eu
kast_exports="$(bash /workspace/kc/profile/packaging/cloud/activate-openai-image.sh)"
eval "$kast_exports"
```

Activation uses the prepared Python environment offline and refreshes the private Gradle proxy and public CA settings.
For another repository or wrapper version, prepare its wrapper before requesting a compiler query:

```sh
bash /workspace/kc/profile/packaging/cloud/prepare-openai-workspace.sh /absolute/repository
```

Workspace preparation grants no IDE trust and performs no project build.
Repository trust remains a separate explicit choice.

The successful build was also installed and probed in the OpenAI cloud container.
After proxy-aware wrapper preparation, it returned four exact Kotlin callers and three Java reference contexts, then rejected an unknown result.
The probe used a separate disposable runtime and fixture; the image profile itself starts no Host and trusts no workspace.

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

If the connector cannot transfer the complete archive, run **Transfer qualified cloud image input**.
Supply the qualified run and setup artifact IDs from the pin file.
The workflow verifies the producer and qualification records, then splits the unchanged archive into two smaller artifacts.
Download both parts through the connector, verify their artifact and part digests, concatenate `archive.part00` and `archive.part01`, and verify the original setup digest again.
This procedure preserves the qualified bytes and performs no native rebuild.

## Image install command

Run as the ordinary environment user on Linux x86-64 with Python 3.12 or 3.13 and uv 0.12.9 or newer.
The composed archive supplies the selected Host runtime and bundled JBR.
Image setup needs no GraalVM, C compiler, full IDE download, or source build.
Use the environment's normal system-package installer if the target image lacks required runtime libraries.

Set these non-secret values in the reviewed setup configuration:

- `CLOUD_IMAGE_ARCHIVE`: the local path to the downloaded setup `.tar.gz`.
- `CLOUD_IMAGE_SHA256`: the approved digest of that archive, not the outer GitHub artifact ZIP.
- `CLOUD_IMAGE_VERSION`: the exact version recorded by the producer.

From the prepared Kast checkout, use this install command:

```sh
set -eu
: "${CLOUD_IMAGE_ARCHIVE:?Select a downloaded qualified archive}"
: "${CLOUD_IMAGE_SHA256:?Pin its approved SHA-256 digest}"
: "${CLOUD_IMAGE_VERSION:?Pin its exact version}"
uv run --locked --project packaging python packaging/cloud/openai-image.py install \
  --archive "$CLOUD_IMAGE_ARCHIVE" --sha256 "$CLOUD_IMAGE_SHA256" \
  --version "$CLOUD_IMAGE_VERSION" --root /workspace/kr
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
  --sha256 "$CLOUD_IMAGE_SHA256" --version "$CLOUD_IMAGE_VERSION" --root /workspace/kr)"
eval "$kast_exports"
```

Check the assignment's exit status before evaluating exports; `set -e` does that here.
Activation adds the wrapper directory to `PATH`.
It preserves the current task's proxy and CA settings, `HOME`, and `JAVA_HOME`.
It refreshes only its marked section in the private Gradle properties file.
The helper starts no Host and accepts no project trust.
Installation and verification JSON report `semanticReadiness: NOT_RUN`.
Receipt and version checks prove no compiler query.

The wrapper uses the bundle's private Host state.
Project dependency downloads still need the current task's approved network access and Java trust configuration.
Validate those connections after task resume; activation reads current proxy values rather than a pinned proxy address.
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
