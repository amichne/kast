---
type: How To
title: "Build isolated runtime candidates"
description: "Build and qualify isolated runtime archives for Apple silicon and Linux x86-64. Prepare Devin snapshot initialization with the Linux candidate."
resource: "repo://docs/composed-linux-release"
tags: [kast, distribution, linux, devin]
timestamp: "2026-11-08T00:00:00Z"
source_revision: aa550bd1b0dff38eb492d20df0658bb62cf44512
code_sources:
  - path: .github/workflows/composed-linux.yml
  - path: packaging/composed_release.py
  - path: packaging/composed_bundle.py
  - path: packaging/composed_host.py
  - path: packaging/composed_host_probe.py
  - path: packaging/test_composition_release.py
---

# Build isolated runtime candidates

Use **Isolated runtime candidates** to produce archives for Apple silicon and Linux x86-64.
The workflow retains candidate archives and qualification evidence.
The Linux x86-64 setup archive also supplies the `kast-action` input.

## Build and inspect the artifact

1. Select a reviewed source revision in the workflow's **Run workflow** form.
   Enter an exact numeric version, such as `0.0.0` for a qualification build.
   For Linux action evidence, enter the full `amichne/kast-action` commit in `action_revision`.
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
The optional Linux action step installs those same archive bytes in another private root.
Its result covers Node action setup. Devin snapshot and session readiness need separate evidence.

The workflow uses read-only repository permissions.
Source admission runs before assembly and before archive upload.
The workflow runs source guards on Linux x86-64 and composition tests on each target.
Jobs that compile Control keep the Native Image build report with the assembly evidence.
The jobs use short canonical temporary paths to fit the native socket path limit.
Use new installation roots for local qualification.

## Prepare the Devin blueprint

Use `examples/devin-blueprint.yaml` from the reviewed `amichne/kast-action` revision that admits the archive installer.
Pin the action to that exact revision.

1. Arrange a separately authorized, reachable artifact location.
   GitHub Actions artifacts are retained build outputs, not permanent release URLs.
   Preserve the qualified archive bytes and its recorded SHA-256 digest.
2. Replace the example's artifact URL, digest, and version.
   Keep the expected version identical in initialization and maintenance.
   Choose a new, writable installation root and keep every blueprint path consistent.
3. Review the blueprint before applying it to a Devin environment.
   Initialization uses system Python 3.12 or 3.13 and invokes the digest-pinned installer action.
   Maintenance checks the existing installation and writes its environment exports to the current `$ENVRC`.
   It does not download, reinstall, upgrade, start the Host, or grant workspace trust.

Devin resets `$ENVRC` on each build, and inherited differential builds skip `initialize`.
The maintenance exports restore `PATH` and `KAST_INSTALL_ROOT` for subsequent steps and the session.
They leave the caller's `HOME` and `JAVA_HOME` unchanged.
An absent installation or a failed version check stops maintenance before export.
The [Devin blueprint reference](https://docs.devin.ai/onboard-devin/environment/blueprint-reference) owns these lifecycle rules.

## Qualification and publication limits

The stable **Release** workflow calls the candidate workflow with the resolved version and release commit.
The Apple silicon job uses the same Control and Host archives as the stable release.
It assembles only the bootstrap in Gradle before runtime assembly.
The Linux x86-64 job assembles its own native Control in parallel with stable candidate preparation.
The stable release publishes the qualified runtime archive and its SHA-256 file for each target.
If a target lacks a matching qualification receipt or digest, the workflow stops before it publishes the release.

Examine the retained evidence for each target and candidate digest.
Publication of a standalone setup archive and use of a Devin blueprint need separate authorization.

Qualify the snapshot build in a separate disposable installation. Keep the reusable installation stopped and without workspace trust.
An environment owner still needs to qualify a full Devin build and an inherited differential build.
In a fresh session after each build, use `kast-task WORKSPACE -- COMMAND [ARGS...]` to select the exact checkout.
Run a compiler query and examine its result. A version response proves command dispatch alone.
The [Linux composition report](reviews/linux-composed-install.md) records the earlier native corpus and its limits.
