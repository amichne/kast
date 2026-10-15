---
type: System Explanation
title: Native management and harness connections
description: Explains the public native command, passive status evidence, connection
  directory admission, registration ownership, and Codex plugins.
tags:
- kast
- distribution
code_sources:
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
- path: packaging/codex-mcp-registration.py
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfigurationContractTest.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallationTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-75e91bf709db8b5e164b1b31
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - id: openwiki-source-2141db5e92ccbb5de75a1544
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - id: openwiki-source-d3ccbe593611586a04efc776
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - id: openwiki-source-927dce901b5dbac4a4d84f48
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Native management and harness connections

## The public command owner

`KastManagementMain` parses the public native `kast` command.
Its finite command model selects status, version, connections, plugin installation, lifecycle operations, upgrades, or uninstall.
The operational JVM command graph and transport launchers remain private payloads.
Their internal command families do not add public semantic subcommands.

Passive status reads installed receipts and available runtime evidence.
The output separates installed and loaded versions, registrations, workspaces, connections, and in-flight requests.
Unavailable evidence stays unavailable with its reason.
Status does not start Java, prepare a workspace, or contact a release server to manufacture readiness.

## Connection destinations and ownership

The command model distinguishes Codex MCP, Codex App Server, Copilot, and Pi connections.
Codex selection needs an explicit transport when the command cannot identify one.
`ConnectionDirectory.admit` establishes a canonical absolute path with bounded printable input.
Physical registration admission separately establishes filesystem ownership before effects.
A syntactically valid directory is not an owned host configuration.

Saved connection configuration holds one admitted directory for each connection.
Registration operations preserve foreign entries and use the selected slot's existing ownership rules.
Replacement and recovery keep the complete configuration and management receipt consistent.
An ambiguous legacy registration does not grant permission to replace another tool's entry.

## Codex plugin installation

`kast plugin codex` first admits the installed launcher and bundled marketplace payload.
It then inspects the exact marketplace and plugin identities through the Codex adapter.
Conflicting marketplace or plugin evidence rejects before registration or installation.
The admitted path asks Codex to install `kast@kast` and examines the resulting state.
Codex owns its configuration, cache, and plugin removal.

Keep plugin installation distinct from direct MCP connection registration.
Both operations can expose Kast tools, but their host-owned configuration and removal rules differ.
Command tests establish parsing and finite outcomes under fixtures.
A native registration in a user's host needs separate effect evidence.

## Focused tests

- [ManagementCliTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ManagementCliTest.kt)
- [ConnectionConfigurationContractTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfigurationContractTest.kt)
- [PluginInstallationTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallationTest.kt)

## Source owners

- [KastManagementMain.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ConnectionConfiguration.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)
- [PluginInstallation.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [codex-mcp-registration.py](../../packaging/codex-mcp-registration.py)
- [ConfigurationSchemaDocument.kt](../../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt)

## Read next

- [Direct tool processes](../transport/direct-mcp-and-tool-rpc.md)
- [Codex hosts](../transport/codex-hosts-and-framing.md)
- [Guarded reset](upgrades-shutdown-and-reset.md)
