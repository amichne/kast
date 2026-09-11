<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: c8efdf63a0ad -->

# cli

## Purpose

Defines Kast's human-facing command graph, installed configuration and lifecycle workflows, and canonical output projections.

## Key Files

- [src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - executable main.
- [src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt](src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt) - root CLI assembly.
- [src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt](src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt) - command topology.
- [src/main/kotlin/io/github/amichne/kast/cli/command/query/QueryCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/query/QueryCommands.kt) - query surface.
- [src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt](src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt) - saved configuration boundary.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) - installation workflow.

- [src/main/kotlin/io/github/amichne/kast/cli/ide](src/main/kotlin/io/github/amichne/kast/cli/ide) - existing-IDE client and semantic read projection.

- [src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt) - intent-tool command projection.

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
