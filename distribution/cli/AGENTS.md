---
type: Engineering Guide
title: "Native management CLI engineering rules"
description: "Defines public command scope, installation ownership, guarded lifecycle transitions, and Codex plugin installation."
resource: "repo://distribution/cli"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
---

# Native management CLI

This module owns the public `kast` executable. Keep its command graph limited to status, version, supported harness registration and plugin installation, upgrade, verified stop/reinstall (with force flags), and uninstall. The operational JVM CLI and transport launchers are private managed payloads.

Keep receipt facts separate from passive runtime observations. Status must not start Java, prepare workspaces, or contact release servers. Admit release manifests and external registration documents before effects; preserve foreign files and registrations. Use the existing installer and service shutdown boundary for verified replacement and removal. Explicit uninstall/reinstall --force bypass old payload and receipt ownership admission. They still require a canonical deletion scope, an exterior reset fence and stable lock, durable retention intent before atomic detachment, and verified retirement of root-scoped jobs and process incarnations before erasure. Remove only the exact selected installation's exterior login entries. Never signal shared IDEA or Gradle processes. Keep deletion scope distinct from ownership; never erase filesystem roots, HOME or its ancestors.

Force lifecycle transitions retain prerequisites in privately constructed types: fenced, quiescent, erased, staged, and active. A single-use lease rejects replayed detach, erase, stage, and activation effects. A staged payload cannot claim active readiness. The bundled installer stages a manifest-qualified reset-capable replacement without service activation; activation success requires the fresh version and service generation. Keep the exterior marker through activation: only persisted ACTIVATING intent plus a held exclusive reset lock admits daemon startup, while tools remain fenced until readiness and final marker removal. Failed staging or activation restores the fence and retires partial startup, preserving both causes if recovery fails. Report retained storage when retirement or cleanup rejects. OS refusal is a finite failure, never a manufactured success. The exterior lock remains stable across directory deletion and subsequent reset attempts.

`kast plugin codex` verifies the complete bundled marketplace and installed MCP launcher before asking Codex to install `kast@kast`. Codex owns plugin configuration, cache, and removal. Preserve foreign marketplace and plugin identities; do not add a second Kast ownership receipt or edit Codex files directly.

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
