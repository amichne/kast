---
type: Module Guide
title: "Kast CLI output"
description: "Defines the shared JSON and TOON presentation adapter for both public executables."
resource: "repo://distribution/output"
tags: [kast, cli, serialization]
timestamp: "2026-11-01T21:22:43Z"
source_revision: 3325655f8ca7d33e04ee3bc01eb3508af3371fbe
code_sources:
  - path: distribution/output/build.gradle.kts
  - path: distribution/output/src/main/kotlin/io/github/amichne/kast/distribution/output/CliOutput.kt
---

# CLI output

This module owns result presentation for both public executables.
`distribution/contract` owns the format enum, parser, and configuration declaration.
The process adapters supply `KAST_OUTPUT_TOON` once at startup.
The existing serializers own result fields, discriminators, defaults, and nulls.
`JToon` encodes their complete JSON projection.

Keep this module free of environment reads, process effects, and output streams.
Keep format failures finite and preserve unsuccessful exit codes in each process adapter.
Do not apply the CLI format switch to stored records or internal protocols.

Run `mise exec -- ./gradlew :distribution:output:scopedCheck` for this module.
The public CLI process tests prove switch admission and the emitted result format.
