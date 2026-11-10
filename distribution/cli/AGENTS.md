---
type: Engineering Guide
title: "Native management CLI engineering rules"
description: "Defines public command scope, installation ownership, guarded lifecycle transitions, and CLI guidance provisioning."
resource: "repo://distribution/cli"
tags: [kast, navigation]
timestamp: "2026-11-10T05:30:08Z"
source_revision: 85d30d515a1d777c5a83781f40813e32e69fa2e6
code_sources:
  - path: distribution/cli/build.gradle.kts
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceDaemonRuntime.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/NativeForceDaemonRuntime.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/NativeControlInvocation.kt
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
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerDispatch.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerExecution.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerCancellation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ContainerCoordinator.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerWorkspace.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlan.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerProcesses.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerRuntime.kt
  - path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ContainerCommandTest.kt
  - path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ContainerPlanTest.kt
---

# Native management CLI

This module owns management commands. `cli` composes them into the single native Control executable.
Route `query`, `result`, `source`, `describe`, and `hook` through the selected `bin/kast-query-complete` launcher with unchanged argv.
The query process mode owns semantic options, admission, output, and public-shell exit policy.
Keep native dispatch thin and preserve management independence from JVM startup.
Forward signals only to the owned query child and reap that child.
Child termination does not prove native semantic settlement.
Compatibility launchers select private modes of the same native executable. Control ships no JVM libraries.

Keep receipt facts separate from passive runtime state.
Status must not start Java, prepare workspaces, or contact release servers.
Admit release manifests and external registration documents before effects.
Preserve foreign files and registrations. Use the existing installer and shutdown owner for replacement and removal.

Explicit `uninstall --force` and `reinstall --force` bypass old payload and receipt ownership admission.
These operations still need a canonical deletion scope, an exterior reset fence, and a stable lock.
Record durable retention intent before atomic detachment.
Prove retirement of root-scoped jobs and process incarnations before erasure.
Force retirement admits native worker modes through `NativeControlEntry`.
Use `admitNativeControlProcessInvocation` for observed process arguments.
Exclude management and installer modes.
Reject unavailable process arguments before retirement or erasure.

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
Codex and Copilot own their plugin resources. Pi uses native skill discovery and a session extension.
All three harnesses call `kast hook session-start` for workspace preparation.
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
`BROKER_NOT_OBSERVED` describes absent broker evidence for private endpoints. It does not describe a compiler epoch or workspace readiness.

The current public `install.sh` remains active during candidate qualification.
The candidate path does not establish stable publication or public bootstrap activation.
Read the [candidate evidence guide](../../docs/reviews/managed-installation-cli.md) for the release limits and source owners.

Run `./gradlew :distribution:cli:test :cli:nativeCompile` for changes here, then the affected release, architecture, JSON, and knowledge guards.

## Container workspaces

`ContainerDispatch` owns native syntax for `kast container shell`, `run`, and `status`.
The native command needs no installed Python launcher, host uv, or Docker CLI.
`kast container --help` needs no installation payload or container runtime.
`ContainerRuntime` coordinates Colima/containerd through `colima nerdctl` in the `kast-native` profile.
`ContainerExecution` uses Lima and containerd's `ctr` client for task transport because this path preserves guest exit codes.
Keep the selected runner explicit. OCI image compatibility does not prove support for another runner.

`ContainerWorkspace` admits the canonical Git worktree and its external Git metadata.
Mount these paths at their original absolute locations so linked worktrees preserve their Git references.
Edits in the container and on the host share the mounted files.
`--name` selects private runtime state and a Gradle cache for the same worktree.
Separate names still share that worktree's files.

`ContainerPlan` owns the expected mounts, network mode, workspace identity, and immutable image selection.
Admit the scope of an existing workspace before image effects.
Preserve the admitted scope through image admission and task startup.
Keep the Gradle cache at `/opt/kast/state/home/.gradle` for both shell commands and IDEA imports.
The retained container keeps IDEA profile and index state.

`ContainerProcesses` owns process capture. `ContainerCoordinator` in `distribution/managed` owns the filesystem lock.
Use the shared child owner for runtime commands and preserve finite failures and literal task arguments.
Release the coordinator lock before the task starts.
Reject concurrent preparation when the coordinator lock is busy.
`ContainerCancellation` signals only the invocation's `ctr` exec ID.
Its bounded result separates transport exit from unproved guest completion. It does not prove detached-descendant retirement.
Container `status` reads existing runtime state without workspace startup or image downloads.
Container admission, cache reuse, and `--help` output do not prove compiler readiness or faster task completion.

## Native compilation inputs

`cli:assembleNativeImageInputs` packages the unified image inputs for the target host.
`cli/native-image.args` owns portable arguments. Its Mac counterpart owns linker arguments.
`collectReachabilityMetadata` supplies resolved metadata for each input archive.
Keep module directories during transfer and raw native compilation.
Routine CI admits one transferred executable before Linux assembly.
It supplies `kastNativeExecutable`. The `cli:nativeCompile` task produces ordinary local builds.

## Navigation

- [KastManagementMain.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt)
- [ManagementCommand.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementCommand.kt)
- [ManagementStatus.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt)
- [ForceInstallationReset.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt)
- [PluginInstallation.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt)
- [ConnectionConfiguration.kt](src/main/kotlin/io/github/amichne/kast/distribution/cli/ConnectionConfiguration.kt)

For connection selection, read `ManagementCommand` and `ConnectionDirectory`.
