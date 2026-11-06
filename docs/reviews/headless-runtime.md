---
type: Engineering Review
title: "Managed headless Host qualification"
description: "Records the owned headless deployment, production acceptance, isolation evidence, and distribution limits."
resource: "repo://docs/reviews/headless-runtime"
tags: [kast, runtime, headless, lifecycle, evidence]
timestamp: "2026-11-06T04:00:00Z"
source_revision: 8367953a41082eabf96b8c5e9a37d7603c4139c9
code_sources:
  - path: packaging/managed_headless.py
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedHeadlessInstallation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedHeadlessLaunch.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/IdeLifecycleDeployment.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ManagedSemanticDeployment.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/ManagedHeadlessHost.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/ManagedHeadlessProjectStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleApplication.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleProjectInspection.kt
  - path: runtime/hosted/src/main/resources/META-INF/plugin.xml
  - path: runtime/hosted/src/headless/resources/META-INF/plugin.xml
---

# Managed headless Host

This candidate runs the existing Host inside a Kast-owned production IntelliJ IDEA distribution.
Control still owns process selection and automatic workspace preparation.
The existing Host still owns Gradle import, compiler queries, source reads, diagnostics, changes, and recovery.
This report grants no release or deployment authority.

## Ownership and admission

`packaging/managed_headless.py` assembles a new private installation from an explicit vendor archive and its SHA-256 digest.
It adds the descriptor-only bootstrap and preserves each kept vendor file byte for byte.
The assembler removes write permissions from the owned runtime.
It invokes the existing public installer for Control and the Host plugin.
It does not start a Host or grant workspace readiness.

The generated `bin/kast` wrapper selects a private HOME and `KAST_MANAGED_HEADLESS_STATE`.
The selected installation, bundled JBR, profile, system directory, logs, and temporary directory travel through the existing lifecycle adapter.
An invalid managed selection rejects before desktop selection or socket access.
The managed launcher does not inspect the developer's IDEA installation or process.

The native starter admits production headless mode and exact private paths before it starts the application endpoint.
It rejects test mode, a forced core classloader, shared state, and a foreign JBR.
The application advertisement carries `MANAGED_HEADLESS`, the loaded build, the exact process incarnation, and disabled auto-reload.
Graphical presentation is absent from the advertised capabilities.

The vendor loader admits `ProjectStorePathCustomizer` only from the bundled bootstrap descriptor.
Its classes remain inside the existing Host plugin.
`ProjectStorePathCustomizer` delegates native storage rules to a private project directory under the system directory.
Its descriptor keeps the actual repository path as the project identity and historical base path.
Admission rejects a different identity, a different metadata directory, or absent external-storage support before native project creation.

Private profiles do not sandbox arbitrary repository build code.
The managed Host analyzes saved files and does not read unsaved buffers from the developer's editor.
Project trust remains necessary.
Startup does not grant trust, and a repository without trust rejects with `TRUST_REQUIRED`.
The disposable qualification fixture receives explicit trust only inside its owned profile.

## Candidate assembly

Use a new absolute root with a short path for Unix sockets.
Supply the exact local component assets and their existing installer sidecars.

```sh
mise exec -- uv run --locked --project packaging python packaging/managed_headless.py \
  --root /private/tmp/khm \
  --archive /absolute/path/idea-2026.2.2-aarch64.dmg \
  --archive-sha256 f4a45f451008c86ce4e9342d5931fd89c8553f9d3ab6e8012da7533e9fe5c249 \
  --bootstrap /absolute/path/kast-managed-headless-v0.0.0-idea-262.zip \
  --bootstrap-sha256 23b90ca038394eaff197b97f30b9e53780adb22ab3b8713de751a3594110891a \
  --assets /absolute/path/paired-assets \
  --version 0.0.0 \
  --build 262.10315.125
```

This example describes candidate assembly.
Its platform-specific result belongs to the acceptance record.
The wrapper uses the existing public query syntax after explicit project trust.

```sh
cd /absolute/path/authorized-repository
/private/tmp/khm/bin/kast query --name Example --kind class --json
```

The immutable assembly record distinguishes the original distribution digest from the bootstrap JAR digest.
The bootstrap input has a separate ZIP digest.
The Host release record and Control payload preserve their own source revisions and artifact digests.
Equal version strings do not establish equal source or compatible contracts.

## Revisions and evidence

The reviewed base and initial refreshed `origin/main` were `1f6db02c16a5dd0e2106f97f7eae747d66545ebe`.
Installed Control and Host artifacts use revision `8367953a41082eabf96b8c5e9a37d7603c4139c9` and tree `9e2530b684edddf6935eade3804d2b1453f689ec`.
Later documentation commits do not change these native artifacts.

The production pin is IDEA `262.10315.125`, Kotlin plugin `262.10315.125-IJ`, and bundled JBR `25.0.4+1-b508.27`.
Production qualification used neither `idea.is.unit.test`, a forced core classloader, nor `Xvfb`.
The bundled test-framework classes did not bootstrap the application.

[The evidence receipt](headless-runtime-evidence.json) records exact inputs, removed file digests, assembly digests, storage, memory, and five warm samples per case.
Detailed traces and qualification drivers remain under the local artifact root that the receipt names.
These traces are not release assets.

| Artifact | SHA-256 |
| --- | --- |
| Linux vendor archive | `f1cc5329a7adf3ab3bd8886744103f7d3bcf1ca12e699762ecd9bffe57335f8b` |
| macOS vendor DMG | `f4a45f451008c86ce4e9342d5931fd89c8553f9d3ab6e8012da7533e9fe5c249` |
| Bootstrap ZIP | `23b90ca038394eaff197b97f30b9e53780adb22ab3b8713de751a3594110891a` |
| Bootstrap JAR | `c61e961b8c2e055732b6ce513d354b36d7018a8d88e9a8b96f22bf3fa1e11da6` |
| Host ZIP | `3bed0f6de55c08fdcfd620e63917c752aef90009d479a850d61a3d530356f086` |
| Host release record | `5627323e4448e38016c89a838ef5c2e1a360a4967e2b5b32cc191f987c53a247` |
| Linux Control archive | `863d7071d7872da6d50f974a3ef8e1a5f85b7bce3d9d97e13c4a769c2b785df4` |
| macOS Control archive | `3cedd346d060d18312b0109f21f4c999263458f4f0becc6103e39856216e0334` |

Linux Control used the existing native-image inputs, Oracle GraalVM `25.0.2+10.1`, GCC 14, and the existing portable arguments.
Its support payload came from the same-revision Control archive.
The recipe replaced only the management executable with the Linux image before archive assembly.
This recipe did not convert the Host into a native image.

## Acceptance corpus

The source fixture starts from `experiments/host-observation/static-callback-fixture` and the existing mixed-reference fixture.
It uses the actual saved Git tree, Gradle `9.8.2`, and Kotlin Gradle plugin `2.4.10`.
Each case uses a new private profile, IDEA system directory, Gradle HOME, and fixture Gradle cache.
The OS page cache is not cold.
The reduced cases restore the same source and model before the same corpus starts.

The public cross-module query uses this command from the fixture root:

```sh
<owned-root>/bin/kast query --name invokeCallback --kind function \
  --in invocation --source-set main --relation callers \
  --fields name,signature,location --json
```

The independent expected callers are `forwardOnce`, `mutualSecond`, `aliasWrapper`, and `mutableAliasWrapper`.
`sharedWrapper` is the nearest excluded case.
All four edges carry exact compiler evidence, with no failures or omissions.
The mixed-language case expects three Java references to the Kotlin nested declaration at independently specified source offsets.

| Stage | Linux full | macOS full | Reduced Linux and macOS |
| --- | --- | --- | --- |
| A: production launch, public cross-module query, metadata isolation | PASSED | PASSED after initial freshness rejection | PASSED |
| B: semantic and lifecycle parity | PASSED | PASSED | PASSED with the same corpus |
| C: whole-plugin reduction | Not selected for baseline | Not selected for baseline | PASSED for three selected plugins |

Stage B includes these independent results and rejection cases:

- A saved, uncommitted declaration appears through public queries, source reads, and diagnostics. Its old source identity rejects.
- `replace_body` rejects invalid syntax without a write. A valid body passes compiler evidence.
- An unresolved body produces `APPLY_UNVERIFIED`. Existing native recovery restores the prior bytes.
- `add_declaration` rejects malformed syntax without a write. A valid declaration appears through a compiler query.
- A new Gradle module adds one caller. Invalid Gradle syntax produces `daemon-operation-lifecycle-import-failed`.
- The restored model returns five callers. The original model and source return four callers.
- Cancellation starts after native transport admission. Native `CANCELLED` precedes connection release, and the public client produces no result.
- Four retained pages reproduce the independent caller set. An unknown retained result rejects.
- A wrong Host UUID rejects. Normal settle and stop retire the exact PID and descriptor.
- Restart creates new application, process, and project identities. Prior exact references and retained result identities reject.
- A fixture without trust produces `TRUST_REQUIRED`. An invalid managed selection produces `UNSUPPORTED_HOST_MODE` without desktop selection.
- Native macOS rejects a second launch against the occupied private profile. The first Host keeps its identity and process.

The initial full macOS query rejected with `FRESHNESS_REJECTED`, `DUMB_MODE`, and `CONTENT_REVALIDATION` after 50.250 seconds.
Native preparation reported `COMPLETE` after 43.159 seconds, before that final semantic freshness rejection.
A later public query passed in 3.726 seconds.
This result does not prove that every first cold query succeeds.
The semantic guard remains strict, and no automatic semantic retry hides this rejection.

## Isolation evidence

Linux qualification preceded native macOS qualification.
The disposable Linux environment uses a dedicated Colima VM, a private ext4 filesystem, four processor cores, and 8 GiB of memory.
Its x86_64 production distribution runs through Rosetta on an ARM host.
No developer HOME, IDEA installation, display socket, or Docker socket enters the container.
Linux measurements do not establish native x86_64 CPU performance.

Linux `inotify` recorded 1,808 workspace events, no protected metadata changes, and no overflow.
`fatrace` recorded 725,288 events and 35 private project-metadata writes.
It also reported six `open_by_handle_at: Cannot allocate memory` errors.
Those errors limit complete file-access attribution.
Independent `inotify` and sentinel-byte results still cover workspace metadata changes.

Native macOS qualification uses the bundled ARM64 JBR and dedicated roots under `/private/tmp`.
A calibrated kernel guard denies personal HOME and personal IDEA application reads and writes for the Host and its descendants.
`FSEvents` recorded 199 events, no protected metadata changes, and no overflow.
Positive write controls and read-only negative controls passed before the corpus.
Both platforms preserve the fixture's `.idea/personal.xml` and `personal.iml` sentinel bytes.
Native project metadata exists only under the owned system directory.

Some early macOS public clients lacked the external kernel guard.
Their persistent Host remained guarded, and later corpus clients used the guard.
System-wide macOS system-call traces was not run because password-free access was absent.
No personal IDE restart, installation change, profile change, or mutable-index access occurred within the guarded Host scope.

Native macOS passive status reports `VERIFIED`, the installed source revision, `MANAGED_HEADLESS`, and disabled auto-reload.
Linux passive process identity reports `PROCESS_IDENTITY_MISMATCH` under Rosetta.
That platform claim remains blocked. The identity gate remains unchanged.

## Whole-plugin reduction

Both reduced installations remove `plugins/code-provenance`, `plugins/fullLine`, and `plugins/grazie` as whole directories.
The recipe removes 124 regular files and no symlinks per platform.
The receipt lists every removed file and its original digest, including actual module JARs.
All kept vendor files preserve their digests after the acceptance corpus.
The recipe preserves `plugins/plugin-classpath.txt` byte for byte.
The private profile records disabled IDs through the existing selected-IDE receipt.

The assembler rejects removal of Host roots and required dependencies.
It keeps platform, Java, Kotlin, and Gradle as Host roots.
It keeps JCEF because the kept `Quarkus` and Swagger components need its module aliases.
Other components keep the pinned-distribution reason.
This selection does not establish a globally minimal distribution.
The native platform also disables `com.intellij.modules.ultimate` in both selections. Its cause remains unknown.

The full official vendor archive remains the assembly input.
The download-size reduction is zero.
A smaller portable shipping archive was not assembled.
The installed-byte reduction is 533,607,153 bytes on Linux and 526,349,864 bytes on macOS.

## Measurements

All byte counts describe apparent regular-file bytes, with allocated bytes separately recorded in the receipt.
Runtime bytes exclude the separately listed Control installation, profile plugins, caches, logs, and temporary files.
These dimensions must not be added as though they describe one archive.

| Metric | Linux full | Linux reduced | macOS full | macOS reduced |
| --- | ---: | ---: | ---: | ---: |
| Vendor archive bytes | 1,606,104,586 | 1,606,104,586 | 1,517,171,147 | 1,517,171,147 |
| Installed runtime bytes | 4,571,234,501 | 4,037,627,348 | 4,459,612,763 | 3,933,262,899 |
| IDEA system bytes | 347,629,139 | 347,741,124 | 348,757,705 | 348,684,957 |
| Private Gradle bytes | 960,588,143 | 960,642,624 | 960,732,940 | 960,781,952 |
| Native preparation seconds | 231.921 | 262.796 | 43.159 | 44.241 |
| Cold public seconds | 258.283 | 291.739 | 50.250, rejected | 58.859 |
| Warm public median seconds, five samples | 5.474 | 6.565 | 2.082 | 1.999 |
| Warm native median milliseconds, five samples | 93.194 | 185.453 | 20.765 | 20.757 |
| Post-corpus summed RSS KiB | 3,441,744 | 3,344,560 | 991,872 | 207,296 |
| Post-corpus summed PSS KiB | 3,409,482 | 3,312,084 | NOT RUN | NOT RUN |

Native preparation measures the existing readiness operation after launch admission.
The separate cold public duration also includes process startup and semantic execution.
Cold process samples include the public process, Host, and Gradle descendants.
Post-corpus measurements also include private Gradle daemons after their parent process exits.
Summed RSS can count shared pages more than once.
The receipt keeps cold peak RSS and Linux heap snapshots separately.
macOS heap snapshots were not run.

The full macOS run overlapped a Linux native build.
The later samples differ in idle time, operating-system residency, and external CPU pressure.
These samples establish measured costs for these runs.
They do not establish a causal latency or memory improvement from plugin removal.

## Checks and remaining work

Focused lifecycle, preparation, assembly, JSON-contract, and architecture checks passed for the installed code revision.
The initial forbidden-process-discovery and premature-preparation-failure cases failed for their intended invariants before their production fixes.
Seven preparation cases and five managed-launch cases passed after those fixes.
Eleven assembly tests passed with Ruff and strict Pyright.
Three external Codex cases skipped in the scoped App Server suite.
Semantic fixture suites remained off under repository policy.
Installed production runs supply the native evidence in this report.

The pinned Vale run passed with readability advisories.
The prose assessment returned `ASSESSED` with grade and sentence-length advisories across the changed pages.
The review report scored grade 10.32 against the advisory target of 8.
Technical identifiers and existing source detail remain intact. Reader comprehension was not assessed.

The required source and product gates must apply to the final committed documentation checkpoint.
The pull request records their exact revision, commands, and outcomes.
Historical source and product gates passed at `468516f4fdfddf12ea3f6322fcb92e8c6244aae8`.
Those historical passes do not establish the final checkpoint result.

```sh
mise exec -- python3 .github/scripts/ci/source_validation.py
mise exec -- python3 distribution/release/run_product_gate.py
```

Class-level reduction, shading, a custom JBR, Host native-image conversion, an IntelliJ source fork, and hot reload remain deferred.
Release publication, deployment, and a new archive format remain outside this checkpoint.
