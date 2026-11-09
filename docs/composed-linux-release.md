---
type: How To
title: "Build a composed Linux bundle for Devin"
description: "Build and retain a source-bound Linux bundle, then prepare Devin snapshot initialization without publishing or changing an environment."
resource: "repo://docs/composed-linux-release"
tags: [kast, distribution, linux, devin]
timestamp: "2026-11-08T00:00:00Z"
source_revision: 08bc4e75693a260c1bfe0ffaad667800934cc0ed
code_sources:
  - path: .github/workflows/composed-linux.yml
  - path: packaging/composed_release.py
  - path: packaging/composed_bundle.py
  - path: packaging/composed_host.py
  - path: packaging/composed_host_probe.py
  - path: packaging/test_composition_release.py
---

# Build a composed Linux bundle for Devin

Use the **Composed Linux bundle** workflow to produce an archive for `kast-action`.
The workflow retains artifacts; it does not publish releases, move action tags, or change Devin environments.
The existing macOS release and developer publication flows stay unchanged.

## Build and inspect the artifact

1. Select a reviewed source revision in the workflow's **Run workflow** form.
   Enter an exact numeric version, such as `0.0.0` for a qualification build.
   The reusable `workflow_call` entry accepts the same version input.
2. Wait for the single Linux job to finish.
   It builds matching Control, Host, and bootstrap components from the checked-out source.
   It checks the pinned donor archive digest before extraction.
   The composition and installation paths use the existing shared owners.
3. Download the successful `kast-composed-linux-SHA-RUN_ID-ATTEMPT` artifact.
   It contains these files:
   - `kast-composed-setup-vVERSION-linux-x86_64.tar.gz`
   - Its `.sha256` companion
   - `assembly.json`, with the source revision and archive digest
   - `qualification.json`, with the same archive digest and native probe result
4. Inspect the separate `kast-composed-evidence-SHA-RUN_ID-ATTEMPT` artifact.
   It retains manifests, installer logs, and native probe output, including failures.
   A failed assembly, probe, or source check blocks the qualified bundle upload.

The workflow installs the produced archive into a fresh private root before it runs the native probe.
The probe checks four exact Kotlin callers, three Java references, exhaustive coverage, and an unknown-result rejection.
It uses a disposable fixture, not the Kast source workspace or the producer installation.
Assembly alone records `NOT_RUN`; only the separate native probe can produce `COMPLETE` qualification.
That result covers the probe corpus, not every Gradle project or a live Devin snapshot.

The workflow has read-only repository permissions and no publication credentials.
Its source admission runs before building and after qualification.
Its short `/tmp/kp`, `/tmp/kc`, and `/tmp/kw` paths belong to the disposable Linux runner.
These paths avoid the native socket path limit.
Do not run the procedure against existing installation roots.

## Prepare the Devin blueprint

Use the updated `examples/devin-blueprint.yaml` from the `amichne/kast-action` checkout.
The installer pin is `8367b18717ff6b016df8acd815f55ce77e7a28e5`; the historical `v2` tag still has the older contract.
The blueprint adds maintenance outside the action, so it does not need an unpublished action revision.

1. Arrange a separately authorized, reachable artifact location.
   GitHub Actions artifacts are retained build outputs, not permanent release URLs.
   Preserve the qualified archive bytes and its recorded SHA-256 digest.
2. Replace the example's artifact URL, digest, and version.
   Keep the expected version identical in initialization and maintenance.
   Choose a new, writable installation root and keep every blueprint path consistent.
3. Review the blueprint before applying it to a Devin environment.
   Initialization installs Python and invokes the digest-pinned installer action.
   Maintenance checks the existing installation and writes its environment exports to the current `$ENVRC`.
   It does not download, reinstall, upgrade, start the Host, or grant workspace trust.

Devin resets `$ENVRC` on each build, and inherited differential builds skip `initialize`.
The maintenance exports restore `PATH` and `KAST_INSTALL_ROOT` for subsequent steps and the session.
They leave the caller's `HOME` and `JAVA_HOME` unchanged.
An absent installation or a failed version check stops maintenance before export.
The [Devin blueprint reference](https://docs.devin.ai/onboard-devin/environment/blueprint-reference) owns these lifecycle rules.

## Qualification and publication limits

The workflow definition and portable tests do not establish a successful hosted Linux build.
Run the workflow after the source changes are delivered, then examine its retained evidence.
Publishing the archive and applying a Devin blueprint remain separate authorized actions.
No release URL is assumed by this procedure.

An environment owner still needs to qualify a full Devin build and an inherited differential build.
After checkout and explicit repository trust, run a real compiler query in the target repository.
A version response proves command dispatch, not imported-model readiness.
The [Linux composition report](reviews/linux-composed-install.md) records the earlier native corpus and its limits.
