---
type: Engineering Review
title: "Composed Linux installation for Kast action"
description: "Describes the portable Linux bundle, action adapter, native evidence, and Devin setup limits."
resource: "repo://docs/reviews/linux-composed-install"
tags: [kast, distribution, linux, evidence]
timestamp: "2026-11-06T17:00:00Z"
source_revision: 13d0ce4b2c41ad781b88b7e1ba9c2c0624df7c04
code_sources:
  - path: bin/setup-kast
  - path: packaging/composition_platform.py
  - path: packaging/composed_host.py
  - path: packaging/composed_bundle.py
  - path: packaging/managed_headless.py
  - path: packaging/composed_host_probe.py
  - path: packaging/test_composition_bundle.py
  - path: packaging/test_composition_host.py
---

# Composed Linux installation for Kast action

The Linux bundle installs the composed analysis Host without an existing IntelliJ installation.
The receiver uses the same `install_prepared_runtime` owner as local composition.
The revived `amichne/kast-action` delegates installation to that receiver.
Its standalone `bin/install-kast` supplies the same procedure outside an action runner.

The [module composition report](composed-analysis-host.md) describes the dependency recipe and its macOS evidence.
This report adds the pinned Linux x86_64 layout and a transferable setup bundle.
It does not establish a minimum dependency set, a CE source build, or full semantic parity.
No composed release or live Devin machine result exists in this change.

## Bundle creation and admission

`composition_platform.py` owns each supported donor digest and runtime layout.
Linux uses the pinned IDEA `262.10315.125` archive with SHA-256 `f1cc5329a7adf3ab3bd8886744103f7d3bcf1ca12e699762ecd9bffe57335f8b`.
The assembler keeps the existing module roots, dependency closure, native index, and selected plugin registrations.
Linux product metadata resides at the runtime root.
The bootstrap adds the production headless starter to the selected classpath.

Create a bundle from a completed Linux composition:

```sh
python3 packaging/composed_bundle.py pack \
  --root /short/composed-root \
  --assets /path/to/paired-assets \
  --bootstrap /path/to/bootstrap.zip \
  --composition-sha256 COMPOSITION_MANIFEST_SHA256 \
  --output /path/to/kast-composed-setup.tar.gz \
  --version 0.0.0
```

The producer examines every composed runtime record against its receipt before it creates the archive.
The bundle includes the paired Control and Host assets, bootstrap descriptor, public installer, and maintained Python receiver.
It does not include the complete donor distribution or mutable producer caches.
`manifest.json` records each runtime and installer file, its kind, length, and digest.
The archive normalizes timestamps and ownership.

Transfer the archive and its SHA-256 digest to the Linux receiver.
The receiver needs Python 3.12 or 3.13 and a new canonical installation root.
From a reviewed Kast checkout, install it with:

```sh
mise exec -- uv run --locked --script bin/setup-kast \
  --archive /path/to/kast-composed-setup.tar.gz \
  --archive-sha256 BUNDLE_SHA256 \
  --root /opt/kast \
  --version 0.0.0 \
  --trust-workspace /path/to/repository
```

The `kast-action` bootstrap can download this archive before it invokes the bundled receiver.
The bootstrap authenticates the archive before it extracts executable bootstrap files.
The receiver authenticates the archive again and rejects unsafe paths, repeated entries, unsupported file kinds, and unrecorded files.
Its archive limits are 100,000 entries and eight GiB of file contents.
Each selected file must match the manifest before installation effects start.
An existing root rejects without replacement.
A failed fresh installation keeps its owned root and installer log for diagnosis.

`assembly-manifest.json` identifies the composed archive as its distribution input.
`bundle-installation.json` preserves that archive digest and the separate original donor digest.
`composition-manifest.json` keeps the original module witnesses and producer file digests.
The bundle manifest independently records the receiver's source digests.
These records distinguish runtime production, installer source, and native component source.

## Private state and trust

The wrapper selects its private `HOME`, caches, Host profile, and bundled JBR.
The action exports only the wrapper directory and `KAST_INSTALL_ROOT` to later steps.
It does not change the caller's `HOME` or `JAVA_HOME`.

The optional `--trust-workspace` argument names one existing canonical repository that the caller explicitly trusts.
The receiver writes that exact root into the new private IntelliJ profile.
It rejects an overlapping installation root or an existing trust file.
Repository trust permits Gradle build code to run.
An absent argument grants no repository trust.
The installer does not start the Host and reports semantic readiness as `NOT_RUN`.

The first semantic request starts the Host and imports the selected repository through the existing lifecycle.
A successful version command proves installation and native dispatch only.
A compiler query supplies separate evidence for that workspace.

## Linux evidence

The local corpus uses Debian 13.6 Linux x86_64 environment in a private Colima VM on an ARM Mac.
Rosetta supplies x86_64 execution.
The fixture and installation reside on the native filesystem of the VM.
The container does not mount the developer HOME or graphical IntelliJ installation.
This result is Linux execution evidence, without a claim about a physical x86_64 host or Devin.

The producer uses Kotlin `262.10315.125-IJ` and bundled JBR `25.0.4+1-b508.27`.
The reused native Control and Host assets come from revision `8367953a41082eabf96b8c5e9a37d7603c4139c9`.
The Python changes in this branch do not rebuild those assets.
The producer's cold query returned the four expected compiler-confirmed callers.
Its coverage was exhaustive, with no failures or omissions.

The portable archive contains 655,834,269 compressed bytes.
Its SHA-256 is `3b09ad8d2dfbe53a3d4db5e384be4e3304dd6ceef62df6212523f5a26ef22cf2`.
The composed runtime keeps 1,655,507,759 regular vendor bytes and adds 1,453,517 generated bytes.
These counts exclude installed native components and mutable caches.
They do not measure memory use or establish a minimum footprint.

The real Node 24 action adapter installed this archive into a fresh Linux root.
Its native version response matched `0.0.0`.
The installed wrapper returned four exact callers and three Java references with exhaustive coverage and no failures or omissions.
It rejected an unknown result reference and kept both repository metadata sentinels unchanged.
A wrong archive digest rejected before creation of the destination.
An existing destination rejected before download and kept its recorded bytes.
The separate [evidence record](linux-composed-install-evidence.json) records the semantic corpus and source identities.

## Devin use and limits

Devin supports Node actions during `initialize` and accepts action exports through `GITHUB_PATH` and `GITHUB_ENV`.
Its action reference needs the `github.com/` prefix and an explicit revision.
The [official blueprint reference](https://docs.devin.ai/onboard-devin/environment/blueprint-reference) owns that environment contract.
The action repository includes a blueprint example and standalone command.

The blueprint needs an immutable action revision, a reachable composed bundle, its digest, and Python 3.12 or 3.13.
The default `/opt/kast` destination must be writable by the environment user.
A blueprint that creates the image before repository checkout must leave workspace trust unset.
After checkout, explicit repository-specific setup can use the standalone command and a new short root.

The local Linux corpus does not prove Devin snapshot behavior, network policy, or workspace provisioning.
A final Devin qualification needs the target machine and the same digest-pinned artifact.
Release publication and a live Devin installation remain separate authorized actions.
