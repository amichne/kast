---
type: System Explanation
title: Installed knowledge projection and retrieval
description: Explains syntax-only declaration extraction, exact module and guide ownership,
  bounded installed resources, and local retrieval.
tags:
- kast
- knowledge
code_sources:
- path: build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt
- path: build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeDocsMain.kt
- path: build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt
- path: build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt
- path: build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeProjection.kt
- path: build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeValidation.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeAdmission.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeResources.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
- path: build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/AdmittedKnowledge.kt
- path: build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledgeTest.kt
- path: build-logic/src/test/kotlin/KotlinDocumentationScannerTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-e7e9afae9a60cee55a46b246
    resource: repo://build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeDocsMain.kt
  - id: openwiki-source-97c69063b7394d01bd57dc64
    resource: repo://build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt
  - id: openwiki-source-e9e7fa3b6fa605695b1a61fb
    resource: repo://build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt
  - id: openwiki-source-20d4273e9ec9b4304314e042
    resource: repo://build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeValidation.kt
  - id: openwiki-source-ed6022ca58911768febfbe7e
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - id: openwiki-source-27de7245d23d2992605b750d
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt
  - id: openwiki-source-b4184b3e65833677c17e8554
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeAdmission.kt
  - id: openwiki-source-d3ccbe593611586a04efc776
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Installed knowledge projection and retrieval

## A packaged syntax reference

The installed knowledge bundle joins public Kotlin declaration text with admitted module ownership and scoped guides.
It does not contain a live semantic index.
`GenerateKnowledgeDocsTask` starts an isolated documentation parser.
`KnowledgeDocsMain` reads canonical Kotlin sources and publishes a closed complete or rejected extraction report.
Unsupported or unreadable sources prevent a success-shaped partial inventory.

`KotlinDocumentationScanner` supplies declaration signatures and direct documentation from PSI syntax.
The extraction does not resolve references or infer inherited documentation.
A declaration card is syntax evidence for the packaged source revision.
It cannot replace a current exact reference from the semantic host.

## Module and guide ownership

The projection consumes the module graph admitted by architecture policy.
Longest-directory ownership must select one module for each declaration.
The generator reads the exact tracked `AGENTS.md` files as canonical regular files.
It keeps full guide content and the guide's own scope and digest.

Module-level guidance uses the module directory.
Declaration-level guidance uses the exact source path.
A nested guide can govern one source subtree without widening to every declaration in its parent module.
Duplicate resources, invalid paths, incorrect guide sets, and oversized resources reject the projection.
The resource limit is 8 MiB.

## Local retrieval

`InstalledKnowledgeReader` first admits the installed manifest.
A simple selection searches shallow descriptors and keeps at most 20 ranked matches.
A JSON resource path selects one admitted manifest, module, declaration, or guide resource.
`KnowledgeResourcePath` accepts only the defined namespaces and canonical components.
Traversal components, foreign resources, changed digests, and mismatched descriptors remain finite lookup failures.

The retained private JVM command family connects to this reader.
The public native management command does not expose that private graph as a public semantic interface.
The private installer process entry admits only its version command.
Read the packaged resources through an admitted consumer rather than assuming a historical command remains public.

## Extension and evidence limits

Extend the typed resource model, projection, manifest admission, and consumer together when a new resource kind is necessary.
Keep syntax provenance and scoped instructions distinct from compiler-grounded identity and current workspace authority.
Projection tests establish exact resource and guide ownership.
Reader tests establish bounded local lookup and rejection, without a running IDEA process.

## Focused tests

- [InstalledKnowledgeProjectionTest.kt](../../build-logic/src/test/kotlin/support/knowledge/InstalledKnowledgeProjectionTest.kt)
- [InstalledKnowledgeTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledgeTest.kt)
- [KotlinDocumentationScannerTest.kt](../../build-logic/src/test/kotlin/KotlinDocumentationScannerTest.kt)

## Source owners

- [GenerateKnowledgeDocsTask.kt](../../build-logic/src/main/kotlin/conventions/GenerateKnowledgeDocsTask.kt)
- [KnowledgeDocsMain.kt](../../build-logic/src/main/kotlin/conventions/jsoncontracts/KnowledgeDocsMain.kt)
- [KotlinDocumentationScanner.kt](../../build-logic/src/main/kotlin/conventions/jsoncontracts/KotlinDocumentationScanner.kt)
- [GenerateInstalledKnowledgeTask.kt](../../build-logic/src/main/kotlin/support/knowledge/GenerateInstalledKnowledgeTask.kt)
- [InstalledKnowledgeProjection.kt](../../build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeProjection.kt)
- [InstalledKnowledgeValidation.kt](../../build-logic/src/main/kotlin/support/knowledge/InstalledKnowledgeValidation.kt)
- [InstalledKnowledge.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt)
- [KnowledgeAdmission.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeAdmission.kt)
- [KnowledgeResources.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/KnowledgeResources.kt)
- [KastCliMain.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ModuleKnowledgeProjection.kt](../../build-logic/src/main/kotlin/support/architecture/knowledge/ModuleKnowledgeProjection.kt)
- [KnowledgeCommands.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt)
- [AdmittedKnowledge.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/knowledge/AdmittedKnowledge.kt)

## Read next

- [Contract projection](../development/contracts-and-generation.md)
- [Wiki upkeep](../development/knowledge-upkeep.md)
- [Public management](../distribution/management-and-connections.md)
