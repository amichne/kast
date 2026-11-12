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
---

# Native management CLI

This module owns management commands. `cli` composes them into the single native Control executable.
The public management verbs are `install`, `status`, `stop`, `versions`, `upgrade`, and `uninstall`.
Route `query`, `result`, `source`, and `describe` through the selected `bin/kast-query-complete` launcher with unchanged argv.
Hooks use the internal query entry point, not a public management verb.
The query process mode owns semantic options, admission, output, and public-shell exit policy.
Keep native dispatch thin and preserve management independence from JVM startup.
Forward signals only to the owned query child and reap that child.
Child termination does not prove native semantic settlement.
Compatibility launchers select private modes of the same native executable. Control ships no JVM libraries.

Keep receipt facts separate from passive runtime state.
Status must not start Java, prepare workspaces, or contact release servers.
Admit release manifests and external registration documents before effects.
Preserve foreign files and registrations. Use the existing installer and shutdown owner for replacement and removal.

Explicit `uninstall --force` bypasses old payload and receipt ownership admission.
It still needs a canonical deletion scope, an exterior reset fence, and a stable lock.
Record durable retention intent before atomic detachment.
Prove retirement of root-scoped jobs and process incarnations before erasure.
Force retirement admits native worker modes through `NativeControlEntry`.
Use `admitNativeControlProcessInvocation` for observed process arguments.
Exclude management and installer modes.
Reject unavailable process arguments before retirement or erasure.

Remove only the exact selected installation's exterior login entries.
Never signal shared IDEA or Gradle processes.
Keep deletion scope distinct from ownership. Never erase filesystem roots, HOME, or its ancestors.

Force removal carries prerequisites in privately constructed fenced, quiescent, and erased states.
A single-use lease rejects repeated destructive effects. Keep `reinstall` retired; exact repair belongs to `upgrade --repair` and the existing replacement journal.
Report kept storage when retirement or cleanup rejects. OS refusal is a finite failure.
The exterior lock stays stable across directory deletion and later reset tries.

`kast install codex|copilot|pi` selects canonical CLI guidance from the admitted installed bundle.
The default composition is `CliOnly`. `--mcp` explicitly selects `CliWithMcp`.
Codex and Copilot own their plugin resources. Pi uses native skill discovery and a session extension.
All three harnesses call `kast --internal-entrypoint=query hook session-start` for passive context, without trust or Host effects.
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

## Managed product and workspace admission

`kast versions [--json]` lists complete stable and preview products. `main` is a compatibility alias for preview.
`kast upgrade` obeys the recorded update policy.
Explicit selectors use `--channel stable|preview`, `--version`, or `--commit`. These selectors are mutually exclusive.
Exact versions and commits create pins. An unqualified upgrade of a pin rejects with `PINNED_BUILD`.
`BuildSelection` owns selector admission. `BuildCatalog` preserves distinct main revision, build revision, and source tree facts.
`ManagementBuilds` resolves one selection and invokes the manifest-owned candidate installer.

`ManagedWorkspaceQuery` owns installed-only admission for CLI, MCP, and tool RPC. Semantic requests never contact release servers.
The first explicit semantic request trusts one exact `CanonicalRoot`. Discovery and hooks do not create trust or workspace state.
Keep the immutable vendor base separate from exact product components and private per-workspace HOME, IDE profile, and default Gradle state.
Saved settings may select an external Gradle home; inherited credentials remain available. Private state is not an OS sandbox.
`stop` retires the selected workspace generation temporarily. Later explicit requests may prepare it again.
Vanished-root reclamation requires an absent root, the exact retired Host generation, no query lease, and settled `.kast` evidence.
Interrupted deletion retains its exterior `.removing-ID.json` receipt. Unknown ownership or process state blocks reclamation.

Keep installed bytes, loaded Host identity, update policy, and unresolved replacement state separate in status.
`PassiveApplicationHost` reads the existing application advertisement and admits its exact process incarnation.
It starts no Java process and sends no lifecycle request.
A loaded Host does not establish workspace readiness or compatibility through version equality.
`BROKER_NOT_OBSERVED` describes absent broker evidence for private endpoints. It does not describe a compiler epoch or workspace readiness.

Source checks do not establish native acceptance, stable publication, or public bootstrap activation.
Read the [candidate evidence guide](../../docs/reviews/managed-installation-cli.md) for the release limits and source owners.

Run focused `:distribution:cli:test` selectors first, then affected contract, architecture, JSON, and knowledge guards. Native compilation and acceptance are separate evidence boundaries.

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
