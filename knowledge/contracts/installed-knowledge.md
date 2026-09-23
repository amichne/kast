---
type: Kotlin Contract
title: Installed knowledge
description: The control product exposes bounded syntax documentation and scoped guides without starting a semantic runtime.
resource: file://cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge
tags: [knowledge, cli, distribution, syntax, guidance]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt
  - path: build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeDocsModel.kt
  - path: build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeDocsMain.kt
  - path: build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt
  - path: build-logic/src/main/kotlin/kast.architecture.gradle.kts
  - path: build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt
  - path: build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeProjection.kt
  - path: build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeValidation.kt
  - path: build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeSourceBinding.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeResources.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/AdmittedKnowledge.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeAdmission.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeInventoryAdmission.kt
  - path: packaging/test-installed-product.sh
---

# Installed knowledge

The retired `kast knowledge` command searched installed declaration descriptors and read listed resources. The assets and parser remain in the control product for now, but the private executable no longer exposes the command. These descriptors concern Kast's shipped source, not the user's open project.

The isolated Kotlin PSI extractor supplies source headers and KDoc. Its output
retains `KOTLIN_PSI_SYNTAX`, Kotlin-only named-declaration coverage, no type
resolution and no inherited documentation. Complete and rejected extraction
reports are closed serialized variants. A failed input cannot produce a partial
success inventory.

The distribution generator consumes the module inventory checked by
`verifyKastArchitecture` and tracked guides. Longest-directory ownership must
resolve to one module. Module-level guidance and declaration-level guidance use
their respective source directories; nested guides are never widened to an
entire parent module.

| Resource | Retained content |
| --- | --- |
| `manifest.json` | Version, source revision, evidence, module and guide descriptors |
| `modules/.../index.json` | Shallow declaration descriptors and module guidance |
| `modules/.../declarations/<id>.json` | Source header, KDoc, source path and exact governing guides |
| `guides/...json` | Complete guide content, scope and SHA-256 |

Generation rejects duplicate identities, resource collisions, incorrect guidance
and resources exceeding 8 MiB. Installed admission retains supported evidence
and canonical resource identities before file effects. Exact reads decode their
specific DTO, verify inventory and ownership, and validate declaration identity
or guide content hashes. Unsupported schema versions, unknown fields, oversized
files, path escapes and symbolic links reject. Search returns at most 20 shallow
matches; it does not read declaration bodies.

The focused parser and projection tests retain the internal asset contract.
`installedProductTest` verifies archive contents and rejects former semantic CLI
commands from the private executable.

The former shell lookup examples are retired. Validate the retained generation
contract with `./gradlew :build-logic:test :cli:check verifyJsonContracts installedProductTest`.
