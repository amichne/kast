<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: f769f85925fc -->

# cli

## Purpose

Owns the private installation and service entry points, installed configuration,
legacy command graph retained for migration, and hosted output projections.

## Key Files

- [KastDaemonMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt) - private managed daemon entry point with exact login admission outside the public command graph.
- [KastServiceMain.kt](src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt) - private installation service control with typed rejection output.
- [KastMcpMain.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt) - installed Kast stdio MCP transport and session tool dispatch.
- [KastDirectToolSession.kt](src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt) - shared direct IDEA tool composition for MCP and harness-neutral RPC.
- [KastToolRpcMain.kt](src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt) - one-shot catalog and invocation boundary for agent extensions.
- [McpWire.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpWire.kt) - typed MCP discovery, request, and result envelopes.
- [McpStructuredResults.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpStructuredResults.kt) - schema-validated MCP result envelopes and concise health and validation summaries.
- [McpApproval.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpApproval.kt) - enrolled key signing for an exact native mutation challenge.
- [McpSingleChangeTool.kt](src/main/kotlin/io/github/amichne/kast/cli/mcp/McpSingleChangeTool.kt) - one-call direct MCP planning, application, and attempted recovery.

- [PackagedProviderCatalog.kt](src/main/kotlin/io/github/amichne/kast/cli/PackagedProviderCatalog.kt) - build-time hosted schema projection for App Server qualification.

- [CanonicalDiagnosticRejectedDocument.kt](src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalDiagnosticRejectedDocument.kt) - finite diagnostic recovery directions and admitted rejection grants.

- [CanonicalReadRejectedDocument.kt](src/main/kotlin/io/github/amichne/kast/cli/projection/CanonicalReadRejectedDocument.kt) - rejected read shapes retain their derived recovery direction and admitted budget.
- [CanonicalReadRejectionSchemas.kt](src/main/kotlin/io/github/amichne/kast/cli/CanonicalReadRejectionSchemas.kt) - canonical finite source and traversal rejection schemas.
- [RelationOmissionSchema.kt](src/main/kotlin/io/github/amichne/kast/cli/RelationOmissionSchema.kt) - bounded provider-omission schema.
- [InstalledBootstrapSchemas.kt](src/main/kotlin/io/github/amichne/kast/cli/InstalledBootstrapSchemas.kt) - shared installed process and bootstrap schemas.
- [src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt](src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt) - executable main.
- [src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt](src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt) - root CLI assembly.
- [src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt](src/main/kotlin/io/github/amichne/kast/cli/command/model/CliCommandGraph.kt) - command topology.
- [src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/tool/PublicToolCommands.kt) - retired public command graph retained as internal migration code.
- [src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt](src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt) - saved configuration boundary.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt) - installation workflow, including committed daemon upgrade resumption.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt) - prior daemon update admission before service retirement.
- [src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt](src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt) - admitted private or legacy prior command and environment retained across sealing and retirement.

- [src/main/kotlin/io/github/amichne/kast/cli/ide](src/main/kotlin/io/github/amichne/kast/cli/ide) - existing-IDE client, semantic read projection, and hosted change/apply/recovery admission and projection.

- [src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt](src/main/kotlin/io/github/amichne/kast/cli/command/knowledge/KnowledgeCommands.kt) - local installed knowledge selector.
- [src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt](src/main/kotlin/io/github/amichne/kast/cli/knowledge/InstalledKnowledge.kt) - shallow search and exact typed resource reads.

## Subdirectories

- `src/main/kotlin/io/github/amichne/kast/cli/bootstrap` - executable and installed-resource assembly.
- `src/main/kotlin/io/github/amichne/kast/cli/command` - command groups and command model.
- `src/main/kotlin/io/github/amichne/kast/cli/configuration` - typed configuration inspection and ingress.
- `src/main/kotlin/io/github/amichne/kast/cli/direct` - shared native tool composition.
- `src/main/kotlin/io/github/amichne/kast/cli/installation` - installation requests and workflow.
- `src/main/kotlin/io/github/amichne/kast/cli/mcp` - on-demand Kast MCP process.
- `src/main/kotlin/io/github/amichne/kast/cli/rpc` - harness-neutral tool RPC process.
- `src/main/resources/mcp` - self-contained MCP Apps validation view.
- `src/main/kotlin/io/github/amichne/kast/cli/projection` - canonical JSON/text documents.
- `src/test` - command, projection, and workflow evidence.

## Entry Points

- Gradle project: `:cli`.
- `install.sh` publishes no command on `PATH`. Private service control lives at
  `share/kast/libexec/kast-service` in the selected installation.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For parsing or command ownership, start with `CliCommandGraph` and the owning command package.
- For output compatibility, start in `projection` and follow to `protocol/wire`.

- Semantic operations use the hosted provider. The installed MCP starts
  preparation for the discovered Gradle root on its first valid request, launching the
  selected IDE when needed; reads wait for its endpoint. The private installed
  control owns registration, lifecycle actions, and trust enrollment. Former
  public semantic commands reject at process ingress.
