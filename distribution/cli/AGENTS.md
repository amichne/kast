---
type: Engineering Guide
title: "Native management CLI engineering rules"
description: "Defines public command scope, installation ownership, guarded lifecycle transitions, and CLI guidance provisioning."
resource: "repo://distribution/cli"
tags: [kast, navigation]
timestamp: "2026-10-30T22:56:37Z"
source_revision: c00f3bfa71dd40e9897d714e378b21eacf673073
code_sources:
  - path: distribution/cli/build.gradle.kts
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BuildSelection.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BuildCatalog.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementBuilds.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PassiveApplicationHost.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/GuidanceInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/SkillExport.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginBundle.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicQueryDispatch.kt
---

# Native management CLI

This module owns the public `kast` executable and its management commands.
Route `query`, `result`, `source`, and `describe` through the selected `bin/kast-query-complete` launcher with unchanged argv.
The query executable owns semantic options, admission, output, and public-shell exit policy.
Keep native dispatch thin and preserve management independence from JVM startup.
Forward signals only to the owned query child and reap that child.
Child termination does not prove native semantic settlement.
The operational JVM CLI and transport launchers remain private managed payloads.

Keep receipt facts separate from passive runtime state.
Status must not start Java, prepare workspaces, or contact release servers.
Admit release manifests and external registration documents before effects.
Preserve foreign files and registrations. Use the existing installer and shutdown owner for replacement and removal.

Explicit `uninstall --force` and `reinstall --force` bypass old payload and receipt ownership admission.
These operations still need a canonical deletion scope, an exterior reset fence, and a stable lock.
Record durable retention intent before atomic detachment.
Prove retirement of root-scoped jobs and process incarnations before erasure.

Remove only the exact selected installation's exterior login entries.
Never signal shared IDEA or Gradle processes.
Keep deletion scope distinct from ownership. Never erase filesystem roots, HOME, or its ancestors.

Force lifecycle transitions carry prerequisites in privately constructed types: fenced, quiescent, erased, staged, and installed.
A single-use lease rejects repeated detach, erase, stage, and completion effects.

The bundled installer stages a manifest-qualified replacement without service activation.
Completion examines the payload again and removes the exact owned reset fence.
`RESET_INSTALLED` carries installation identity, version, and command. It carries no service generation or readiness claim.
No success path starts a broker or publishes Codex discovery.

Failed staging or completion restores the fence and retires partial processes, with both causes if recovery fails.
Report kept storage when retirement or cleanup rejects. OS refusal is a finite failure.
The exterior lock stays stable across directory deletion and later reset tries.

`kast install codex|copilot|pi` selects canonical CLI guidance from the admitted installed bundle.
The default composition is `CliOnly`. `--mcp` explicitly selects `CliWithMcp`.
Codex and Copilot own their plugin resources. Pi uses native skill discovery.
Preserve foreign provider resources and reject unresolved conflicts before effects.
Provisioning success describes installed resources. It does not describe an existing agent session.

`kast install skill [--output <directory>]` exports the canonical skill without provider registration.
Identical content returns without writes. Changed, extra, or linked entries reject without overwriting bytes.
`connect` and `plugin` setup syntax rejects before effects and names `kast install` as its replacement.
Keep legacy ownership readers only for migration and exact cleanup.

Public reset removes the fixed historical paths in `PublicResetScope` and known launcher backups after process retirement.
Keep unrelated extensions and stable locks. Reject ancestor aliases before destructive effects.

The managed root contains one ordinary `installation` directory. Its stable `bin`, `share`, `config`, and `state` paths survive replacement. Ordinary installation locks and the management receipt remain in the managed root; the destructive reset fence and stable lock live beside it. Do not introduce version directories, selection links, or native legacy-layout fallback; the installer owns admitted migration and transaction recovery.

Legacy connection migration needs the recorded MCP command and a proven `PAYLOAD_COMMITTED` replacement transaction.
Preserve the replacement receipt, old launcher digest, filesystem identities, selector target, and actual Codex command.
If migration fails, restore the provider configuration and management receipt. The installer owns terminal cleanup.

## Managed installation candidate

`kast versions [--json]` lists stable and the captured catalog of qualified main builds.
`kast upgrade` obeys the recorded update policy.
Explicit selectors use `--channel stable|main`, `--version`, or `--commit`. These selectors are mutually exclusive.
Exact versions and commits create pins. An unqualified upgrade of a pin rejects with `PINNED_BUILD`.
`BuildSelection` owns selector admission. `BuildCatalog` preserves distinct main revision, build revision, and source tree facts.
`ManagementBuilds` resolves one selection and invokes the manifest-owned candidate installer.

Keep installed bytes, loaded Host identity, update policy, and unresolved replacement state separate in status.
`PassiveApplicationHost` reads the existing application advertisement and admits its exact process incarnation.
It starts no Java process and sends no lifecycle request.
A loaded Host does not establish workspace readiness or compatibility through version equality.

The current public `install.sh` remains active during candidate qualification.
The candidate path does not establish stable publication or public bootstrap activation.
Read the [candidate evidence guide](../../docs/reviews/managed-installation-cli.md) for the release limits and source owners.

Run `./gradlew :distribution:cli:test :distribution:cli:nativeCompile` for changes here, then the affected release, architecture, JSON, and knowledge guards.

## Native compilation inputs

`assembleNativeImageInputs` packages JVM libraries and the shared Native Image arguments for the target host.
`native-image.args` owns portable arguments. `native-image-macos.args` owns Mac linker arguments.
`collectReachabilityMetadata` supplies resolved metadata for each input archive.
Keep module directories during transfer and raw native compilation.
Routine CI admits both transferred executables before Linux assembly.
It supplies `kastNativeExecutable` and `kastQueryNativeExecutable` together.
Ordinary local builds keep `nativeCompile` as the executable producer.

## Navigation

- [KastManagementMain.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ForceInstallationReset.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt)
- [PluginInstallation.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [ConnectionConfiguration.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)

For connection selection, read `ManagementCommand` and `ConnectionDirectory`.
