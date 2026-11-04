---
type: Module Guide
title: "Kast CLI output"
description: "Defines the shared JSON and TOON presentation adapter for public command modes."
resource: "repo://distribution/output"
tags: [kast, cli, serialization]
timestamp: "2026-11-04T23:05:59Z"
source_revision: 89e9bcf0de1ca8f338ffd629fd7ec4606266d4bc
code_sources:
  - path: distribution/output/build.gradle.kts
  - path: distribution/output/src/main/kotlin/io/github/amichne/kast/distribution/output/CliOutput.kt
  - path: distribution/output/src/main/kotlin/io/github/amichne/kast/distribution/output/JsonProjectionDocument.kt
  - path: distribution/output/src/main/kotlin/io/github/amichne/kast/distribution/output/LosslessCliOutput.kt
---

# CLI output

This module owns result presentation for public command modes.
`distribution/contract` owns the format enum, parser, and configuration declaration.
The process adapters supply `KAST_OUTPUT_TOON` once at startup.
The existing serializers own result fields, discriminators, defaults, and nulls.
`JToon` encodes their complete JSON projection.

Use `JsonProjectionDocument` to admit a dynamic user projection as one strict JSON value.
`renderLosslessCliOutput` preserves exact JSON numeric tokens and checks decoded TOON values with exact decimal comparisons.
Reject changed values, keys, nulls, and array shapes. Keep the TOON depth bound before encoding.

Keep this module free of environment reads, process effects, and output streams.
Keep format failures finite and preserve unsuccessful exit codes in each process adapter.
Do not apply the CLI format switch to stored records or internal protocols.

Run `mise exec -- ./gradlew :distribution:output:scopedCheck` for this module.
The public CLI process tests prove switch admission and the emitted result format.
