---
type: Engineering Review
title: "Module-based analysis Host"
description: "Describes the module-based assembly, native qualification, and limits of the pinned analysis Host."
resource: "repo://docs/reviews/composed-analysis-host"
tags: [kast, runtime, headless, dependencies, evidence]
timestamp: "2026-11-06T09:15:00Z"
source_revision: 13d0ce4b2c41ad781b88b7e1ba9c2c0624df7c04
code_sources:
  - path: packaging/composed_host.py
  - path: packaging/composition_graph.py
  - path: packaging/composition_index.py
  - path: packaging/composition_descriptors.py
  - path: packaging/composition_recipe.py
  - path: packaging/composed_host_probe.py
  - path: packaging/managed_headless.py
  - path: packaging/test_composition_graph.py
  - path: packaging/test_composition_index.py
  - path: packaging/test_composition_descriptors.py
  - path: packaging/test_composition_host.py
---

# Module-based analysis Host

The assembler creates an empty runtime tree and copies files selected through compiled modules and native plugin registrations.
It selects PSI, indexes, Kotlin analysis, and Gradle import through explicit roots.
The existing Host supplies the semantic operations.
The existing installer and lifecycle adapters supply private state, startup, trust admission, cancellation, and stop.

This candidate uses the pinned vendor distribution as a source cache.
It does not rebuild Community Edition from source or resolve separate Maven artifacts.
Its smaller installed footprint does not establish a minimum dependency set.
This report grants no release or deployment authority.

The [Linux installation report](linux-composed-install.md) adds a portable bundle and the action adapter.
Its evidence remains separate from this macOS corpus.

## Composition and evidence

`composition_recipe.py` names the roots and twelve provider candidates.
Required dependencies select the actual providers.
`composition_graph.py` reads only selected descriptors and their transitive dependencies from `modules/module-descriptors.jar`.
Each module keeps its exact name, namespace, dependency predecessor, and descriptor digest.
It rejects unknown modules, duplicate declarations, unsafe resources, and exceeded limits.

`composition_descriptors.py` adds required dependencies from native plugin descriptors and selected content modules.
It preserves each content namespace and embedded XML descriptor.
An ambiguous module name rejects instead of selecting the first provider.
Archive support remains separate from semantic registrations.
An embedded archive can contain unused classes without permission to load its optional feature descriptors.

`composition_index.py` implements the pinned loader's version-2 binary index.
The independent byte oracle covers its version, normalization byte, lengths, plugin count, descriptors, and classpath entries.
The parser rejects unsupported versions, incomplete input, duplicate directories, unsafe paths, and trailing bytes.
The generated native index selects plugin registrations without changes to vendor JARs.

`composed_host.py` examines the source inventory against an explicit manifest digest.
It examines every selected source file before the copy and compares each copied file against its source record.
It generates three runtime files: product metadata, the native plugin index, and `lib/kast-product.jar`.
The product JAR supplies the core descriptor and application metadata.
The Community Edition starter comes from the selected `lib/ext/platform-main.jar`.

The native launcher needs `productVendor` in the product metadata.
The lowercase `idea.platform.prefix` selects `idea/ApplicationInfo.xml`.
The runtime uses production headless mode without test mode or a forced core classloader.
`install_prepared_runtime` keeps the existing bootstrap, installer, private state, and wrapper under one owner.

The Kotlin descriptor omits `ultimate-plugin.k2.xml` and `command-completion-k2.xml`.
These fragments add optional product features and command completion outside the semantic corpus.
An unexpected Kotlin include rejects the recipe.
The recipe keeps `intellij.kotlin.codeInsight.base` because it registers platform tooling needed by Gradle import.

The composition manifest records source inputs, module witnesses, selected providers, omitted registrations, vendor files, generated files, and assembler digests.
Its acceptance field remains `NOT_RUN` after assembly.
A separate [native evidence record](composed-analysis-host-evidence.json) records runtime results.

## Native qualification

The candidate uses IDEA `262.10315.125`, Kotlin plugin `262.10315.125-IJ`, and bundled JBR `25.0.4+1-b508.27`.
The reused native Host and Control payloads come from revision `8367953a41082eabf96b8c5e9a37d7603c4139c9`.
The composition manifest separately identifies the Python assembler files.
Equal version strings do not establish equal source.

The recipe selects 528 compiled modules and 12 plugin providers, including the core provider.
The file inventories give this comparison:

| Measure | Donor distribution | Composed runtime |
| --- | ---: | ---: |
| Regular vendor files | 3,188 | 761 |
| Regular vendor bytes | 4,459,611,689 | 1,646,303,144 |
| Symbolic links | 222 | 221 |

The composed runtime adds 1,453,785 bytes of generated metadata.
These counts exclude Control, installed Host files, mutable caches, and the bootstrap descriptor.
They describe file contents, without a claim about compressed downloads, filesystem allocation, or memory use.

The native corpus uses a disposable repository and a private profile with explicit trust for that repository.
A macOS sandbox denies access to the developer HOME and graphical IDEA installation.
The fixture keeps unrelated `.idea/personal.xml` and `personal.iml` sentinels.
The corpus examines their bytes after semantic operations.

The corpus covers these behaviors and rejection cases:

- Four compiler-confirmed cross-module callers, with exhaustive coverage and no failures or omissions.
- Three Java references to a Kotlin class, with independently enumerated token ranges and contexts.
- Saved source admission, a fresh source read, stale-reference rejection, and compiler diagnostics.
- Body replacement and declaration addition, syntax rejection without source changes, and recovery after failed diagnostics.
- A new Gradle module, rejected readiness after failed import, and admission after restored import.
- Bounded retained pages, an unknown-result rejection, and cancellation after actual read admission.
- Trust rejection, invalid managed selection, wrong-Host rejection, normal stop, and a new process incarnation after restart.

The retained `composed_host_probe.py` exercises callers, Java references, and unknown-result rejection through the public CLI.
The broader corpus and its raw evidence remain in the local artifact directory named in the evidence record.
Those local artifacts supplement the retained probe and portable cases.
They are not public CI artifacts.

## Assembly and probe

Use a new absolute destination with a short path for Unix sockets.
Supply the previously receipted donor installation and its original assembly manifest.
Supply the matching source archive, bootstrap archive, and paired component assets.

```sh
mise exec -- uv run --locked --project packaging python packaging/composed_host.py \
  --root /private/tmp/kbh \
  --source-home "/absolute/donor/IntelliJ IDEA.app/Contents" \
  --source-manifest /absolute/donor/assembly-manifest.json \
  --source-manifest-sha256 SOURCE_MANIFEST_SHA256 \
  --archive /absolute/path/idea-2026.2.2-aarch64.dmg \
  --archive-sha256 f4a45f451008c86ce4e9342d5931fd89c8553f9d3ab6e8012da7533e9fe5c249 \
  --bootstrap /absolute/path/bootstrap.zip \
  --bootstrap-sha256 23b90ca038394eaff197b97f30b9e53780adb22ab3b8713de751a3594110891a \
  --assets /absolute/path/paired-assets \
  --version 0.0.0
```

After explicit trust and fixture preparation, run the retained probe.
Use a fresh evidence directory.

```sh
mise exec -- uv run --locked --project packaging python packaging/composed_host_probe.py \
  --root /private/tmp/kbh \
  --fixture /private/tmp/kbw6 \
  --evidence /absolute/path/new-probe-evidence \
  --sandbox /absolute/path/mac-isolation.sb
```

The fixture starts from `experiments/host-observation/static-callback-fixture` with the repository Gradle wrapper.
The `experiments/host-observation/composed-host-fixture-overlay` directory supplies the Java-reference files.
The private profile grants trust only to the exact disposable root.
The probe does not grant trust.

## Limits and next reductions

The current recipe supports only the pinned macOS ARM distribution.
The source cache still contains the full donor distribution.
The output keeps complete selected vendor archives, JBR, native support, and the Kotlin compiler distribution.
Compiled dependencies still include platform UI and execution classes.
The recipe does not establish that every selected class is necessary.

The [JetBrains Research analysis server](https://github.com/JetBrains-Research/code-quality-ij-server) supports the companion-server design.
Its small application still depends on a complete IDEA runtime.
The [platform module sets](https://github.com/JetBrains/intellij-community/blob/master/platform/build-scripts/src/org/jetbrains/intellij/build/productLayout/CoreModuleSets.kt) provide a source-backed route toward a smaller platform product.
That route needs a separate native comparison before it replaces this pinned recipe.

The next reduction can target platform roots or archive layout while it preserves the same semantic corpus.
This candidate proves native composition and the stated parity cases.
It does not establish redistribution rights, general Gradle compatibility, multiplatform parity, or public release readiness.
