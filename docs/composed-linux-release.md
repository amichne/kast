---
type: How To
title: "Build isolated runtime candidates"
description: "Build and qualify managed product runtime inputs for Apple silicon and Linux x86-64."
resource: "repo://docs/composed-linux-release"
tags: [kast, distribution, linux, devin]
timestamp: "2026-11-08T00:00:00Z"
source_revision: aa550bd1b0dff38eb492d20df0658bb62cf44512
code_sources:
  - path: .github/workflows/composed-linux.yml
  - path: packaging/internal/composition/composed_release.py
  - path: packaging/composed_bundle.py
  - path: packaging/internal/composition/composed_host.py
  - path: packaging/internal/verification/composed_host_probe.py
  - path: packaging/tests/test_composition_release.py
---

# Build isolated runtime candidates

Use **Isolated runtime candidates** to produce archives for Apple silicon and Linux x86-64.
The workflow retains candidate archives and qualification evidence.
The setup archive supplies authenticated inputs to the complete product installer, not a separate Python setup command.

## Build and inspect the artifact

1. Select a reviewed source revision in the workflow's **Run workflow** form.
   Enter an exact numeric version, such as `0.0.0` for a qualification build.
   Leave `action_revision` empty; the historical archive action contract is retired.
2. Wait for **Build and qualify** on your target.
   Each job builds matching Control, Host, and bootstrap components from the checked-out source.
   It checks the pinned donor archive digest before extraction.
   It uploads the archives and qualifies the same local files by digest.
3. Download `kast-composed-TARGET-SHA-RUN_ID-ATTEMPT` for the chosen target.
   It contains these files:
   - `kast-composed-setup-vVERSION-TARGET.tar.gz`
   - `kast-runtime-vVERSION-TARGET.zip`
   - The `.sha256` companion for each archive
   - `assembly.json`, with the source revision and archive digest
4. Inspect `kast-composed-evidence-TARGET-SHA-RUN_ID-ATTEMPT` for the same run.
   It keeps the installer logs, probe output, and `qualification.json` when qualification completes.
   Match the source revision and archive digest across the two artifacts.

The qualification step installs the produced archive into a fresh private root on its target system.
The probe uses a disposable fixture for compiler results and Host lifecycle evidence.
Assembly records `NOT_RUN`. The native probe records `COMPLETE` when its checks pass.
Assembly does not install Control or create a mutable profile. It stages admitted components for archive production.
The archive contains the canonical generated `install.sh`; it contains no standalone `.pyz` installer.
Native qualification consumes exact component bytes separately from public installation.

The workflow uses read-only repository permissions.
Source admission runs before assembly and before archive upload.
The workflow runs source guards on Linux x86-64 and composition tests on each target.
Jobs that compile Control keep the Native Image build report with the assembly evidence.
The jobs use short canonical temporary paths to fit the native socket path limit.
Use new installation roots for local qualification.

## Use the managed product installer

Use the [installation reference](public/reference/installation.mdx) for public setup after a complete product is published.
It downloads pinned private Python and dependency bytes without ambient Python, uv, or pip.
This does not make setup offline. Producer tooling still uses Python and uv before delivery.

Historical `kast-action` archives, Devin blueprints, and `kast-task` launchers used a different installation contract.
They do not consume the current archive format. Keep historical evidence tied to its recorded source and artifact digests.
An external action migration and new Devin qualification need separate work; do not infer support from these archives.

## Qualification and publication limits

The stable **Release** workflow calls the candidate workflow with the resolved version and release commit.
The Apple silicon job uses the same Control and Host archives as the stable release.
It assembles only the bootstrap in Gradle before runtime assembly.
The Linux x86-64 job assembles its own native Control in parallel with stable candidate preparation.
The stable release publishes the qualified runtime archive and its SHA-256 file for each target.
If a target lacks a matching qualification receipt or digest, the workflow stops before it publishes the release.

Examine the retained evidence for each target and candidate digest.
Publication of a standalone setup archive and use of a Devin blueprint need separate authorization.

Qualify each product in a disposable installation on its target platform.
From a fresh workspace, run a compiler query and examine its result. A version response proves command dispatch alone.
The [Linux composition report](reviews/linux-composed-install.md) records the earlier native corpus and its limits.
