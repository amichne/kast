<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: f769f85925fc -->

# cli

## Purpose

Defines Kast's human-facing command graph, installed configuration and installation workflows, and canonical output projections.

## Key Files

- [KastDaemonMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt) - private managed daemon entry point outside the public command graph.
- [KastServiceMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt) - private installation service control with typed rejection output.

- [PackagedProviderCatalog.kt](src/main/kotlin/io/github/amichne/kast/cli/PackagedProviderCatalog.kt) - build-time hosted schema projection for App Server qualification.

- [WorkspaceRefreshAdmission.kt](src/main/kotlin/io/github/amichne/kast/cli/ide/WorkspaceRefreshAdmission.kt) - strict refresh control admission and operation-specific process outcomes.

- [CanonicalDiagnosticRejectedDocument.kt](src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalDiagnosticRejectedDocument.kt) - finite diagnostic recovery directions and admitted rejection grants.

- [CanonicalReadRejectedDocument.kt](src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalReadRejectedDocument.kt) - rejected read shapes retain their derived recovery direction and admitted budget.
- [CanonicalReadRejectionSchemas.kt](src/main/kotlin/io/github/amichne/kast/cli/CanonicalReadRejectionSchemas.kt) - canonical finite source, relation and traversal rejection schemas.
- [RelationOmissionSchema.kt](src/main/kotlin/io/github/amichne/kast/cli/RelationOmissionSchema.kt) - bounded provider-omission schema.
- [InstalledBootstrapSchemas.kt](src/main/kotlin/io/github/amichne/kast/cli/InstalledBootstrapSchemas.kt) - shared installed process and bootstrap schemas.
- [src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - executable main.
- [src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt](src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt) - root CLI assembly.
- [src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt](src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt) - command topology.
- [src/main/kotlin/io/github/amichne/kast/cli/command/query/QueryCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/query/QueryCommands.kt) - query surface.
- [src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt](src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt) - saved configuration boundary.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) - installation workflow, including committed daemon upgrade resumption.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt) - prior daemon update admission before service retirement.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt) - admitted prior command and environment retained across sealing and retirement.

- [src/main/kotlin/io/github/amichne/kast/cli/ide](src/main/kotlin/io/github/amichne/kast/cli/ide) - existing-IDE client, semantic read projection, and hosted change/apply/recovery admission and projection.

- [src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt) - intent-tool command projection.

- [src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt) - local installed knowledge selector.
- [src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt](src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt) - shallow search and exact typed resource reads.

## Subdirectories

- `src/main/kotlin/io/github/amichne/kast/cli/bootstrap` - executable and installed-resource assembly.
- `src/main/kotlin/io/github/amichne/kast/cli/command` - command groups and command model.
- `src/main/kotlin/io/github/amichne/kast/cli/configuration` - typed configuration inspection and ingress.
- `src/main/kotlin/io/github/amichne/kast/cli/installation` - installation requests and workflow.
- `src/main/kotlin/io/github/amichne/kast/cli/projection` - canonical JSON/text documents.
- `src/test` - command, projection, and workflow evidence.

## Entry Points

- Gradle project: `:cli`.
- Public shell entry is installed by `install.sh` and packaging scripts.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For parsing or command ownership, start with `CliCommandGraph` and the owning command package.
- For output compatibility, start in `projection` and follow to `protocol/wire`.

- Semantic operations require an existing IDE endpoint. Bare inspection is passive; retired `start`, `stop`, and `status` commands are absent. App-server, broker, IDE, product inspection, and workspace lifecycle operations are visible in help.
