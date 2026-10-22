---
type: Engineering Guide
title: "Native management CLI engineering rules"
description: "Defines public command scope, installation ownership, guarded lifecycle transitions, and Codex plugin installation."
resource: "repo://distribution/cli"
tags: [kast, navigation]
timestamp: "2026-10-21T22:40:00Z"
source_revision: 822bd6a9d43bde00bad3e756529a71de8c9a82b9
code_sources:
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginBundle.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
---

# Native management CLI

This module owns the public `kast` executable. Keep its command graph limited to status, version, supported harness registration and plugin installation, upgrade, verified stop/reinstall (with force flags), and uninstall. The operational JVM CLI and transport launchers are private managed payloads.

Keep receipt facts separate from passive runtime observations. Status must not start Java, prepare workspaces, or contact release servers. Admit release manifests and external registration documents before effects; preserve foreign files and registrations. Use the existing installer and service shutdown boundary for verified replacement and removal. Explicit uninstall/reinstall --force bypass old payload and receipt ownership admission. They still require a canonical deletion scope, an exterior reset fence and stable lock, durable retention intent before atomic detachment, and verified retirement of root-scoped jobs and process incarnations before erasure. Remove only the exact selected installation's exterior login entries. Never signal shared IDEA or Gradle processes. Keep deletion scope distinct from ownership; never erase filesystem roots, HOME or its ancestors.

Force lifecycle transitions carry prerequisites in privately constructed types: fenced, quiescent, erased, staged, and installed.
A single-use lease rejects repeated detach, erase, stage, and completion effects.

The bundled installer stages a manifest-qualified replacement without service activation.
Completion examines the payload again and removes the exact owned reset fence.
`RESET_INSTALLED` carries installation identity, version, and command. It carries no service generation or readiness claim.
No success path starts a broker or publishes Codex discovery.

Failed staging or completion restores the fence and retires partial processes, with both causes if recovery fails.
Report kept storage when retirement or cleanup rejects. OS refusal is a finite failure.
The exterior lock stays stable across directory deletion and later reset tries.

`kast plugin codex` verifies the complete bundled marketplace and installed MCP launcher before asking Codex to install `kast@kast`. Codex owns plugin configuration, cache, and removal. Preserve foreign marketplace and plugin identities; do not add a second Kast ownership receipt or edit Codex files directly.

`kast connect codex` and `kast connect codex mcp` select that same plugin owner.
Reject new App Server registration before effects. Keep legacy receipt variants only for exact owned cleanup and plugin migration.
Preserve unrecorded or foreign MCP entries. Reject their conflict before plugin installation.
The exact transport from the manifest-admitted plugin bundle needs no legacy receipt.
Codex's merged MCP list can contain that transport during repeated installation.

Public reset removes the fixed historical paths in `PublicResetScope` and known launcher backups after process retirement.
Keep unrelated extensions and stable locks. Reject ancestor aliases before destructive effects.

The managed root contains one ordinary `installation` directory. Its stable `bin`, `share`, `config`, and `state` paths survive replacement. Ordinary installation locks and the management receipt remain in the managed root; the destructive reset fence and stable lock live beside it. Do not introduce version directories, selection links, or native legacy-layout fallback; the installer owns admitted migration and transaction recovery.

The connection owner may migrate an exactly recorded legacy MCP command only during a proven `PAYLOAD_COMMITTED` legacy replacement transaction. Preserve the canonical replacement receipt, old launcher digest, filesystem identities, selector target, and observed Codex command proof. Restore the complete host configuration and management receipt if the connection transaction fails; the installer seals replacement recovery.

Run `./gradlew :distribution:cli:test :distribution:cli:nativeCompile` for changes here, then the affected release, architecture, JSON, and knowledge guards.

## Navigation

- [KastManagementMain.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ForceInstallationReset.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt)
- [PluginInstallation.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [ConnectionConfiguration.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)

For connection selection, read `ManagementCommand` and `ConnectionDirectory`.
