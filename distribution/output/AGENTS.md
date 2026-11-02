---
type: Module Guide
title: "Kast CLI output"
description: "Defines the shared JSON and TOON presentation adapter for public command modes."
resource: "repo://distribution/output"
tags: [kast, cli, serialization]
timestamp: "2026-11-02T16:49:19Z"
source_revision: 2e45174f6c6095c0f775e12d4c6c3d8b562f35f3
code_sources:
  - path: distribution/output/build.gradle.kts
  - path: distribution/output/src/main/kotlin/io/github/amichne/kast/distribution/output/CliOutput.kt
---

# CLI output

This module owns result presentation for public command modes.
`distribution/contract` owns the format enum, parser, and configuration declaration.
The process adapters supply `KAST_OUTPUT_TOON` once at startup.
The existing serializers own result fields, discriminators, defaults, and nulls.
`JToon` encodes their complete JSON projection.

Keep this module free of environment reads, process effects, and output streams.
Keep format failures finite and preserve unsuccessful exit codes in each process adapter.
Do not apply the CLI format switch to stored records or internal protocols.

Run `mise exec -- ./gradlew :distribution:output:scopedCheck` for this module.
The public CLI process tests prove switch admission and the emitted result format.
