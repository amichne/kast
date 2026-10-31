---
type: Engineering Review
title: "Managed installation and CLI guidance candidate"
description: "Records candidate source owners, focused evidence, recovery meanings, and pending public rollout."
resource: "repo://docs/reviews/managed-installation-cli"
tags: [kast, installation, lifecycle, evidence]
timestamp: "2026-10-30T22:56:37Z"
source_revision: c00f3bfa71dd40e9897d714e378b21eacf673073
code_sources:
  - path: install.sh
  - path: packaging/install-candidate.sh
  - path: packaging/managed_installation.py
  - path: packaging/managed_release.py
  - path: packaging/managed_update_contract.py
  - path: packaging/managed_update_journal.py
  - path: packaging/host-installation.py
  - path: packaging/reload_hosted.py
  - path: packaging/installation-recovery.py
  - path: packaging/run-installed-product.py
  - path: distribution/release/component_release.py
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedControlAdapter.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedControlAdmission.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedHostBridge.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementBuilds.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PassiveApplicationHost.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedApplicationSettlement.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/IdeLifecycleDocuments.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ObservedProcessState.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedProcessCapture.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedProcessState.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/LegacyProfileSelection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
---

# Managed installation candidate

This report supports review and qualification of the managed installation candidate.
It describes uncommitted candidate changes on the recorded base revision.
It does not record a published release or an active public bootstrap change.
The current `install.sh` and its stable installation instructions remain unchanged.

The October 30 implementation specification separates candidate qualification from public rollout.
Implementation completion needs A1–A15 and the repository guards.
Default-flow completion also needs stable publication, public bootstrap activation, and A16 through that public entry.
Those release steps remain pending in this report.

## Source owners

| Concern | Owner | Established scope |
| --- | --- | --- |
| Public management syntax | `KastManagementMain`, `ManagementCommand` | `versions`, `upgrade`, and `install` admission |
| Build selection | `BuildSelection`, `BuildCatalog`, `ManagementBuilds` | One selected identity and recorded update policy |
| Asset admission | `managed_release.py` | Candidate, promotion, manifest, target, and digest facts |
| Managed composition | `managed_installation.py`, `managed_update_contract.py` | Ordered effects, terminal outcomes, and recovery |
| Shared recovery records | `managed_update_journal.py` | Existing replacement receipt and exact preimages |
| Control replacement | `ManagedControlAdapter`, `InstallationWorkflow` | Existing staging, retention, replacement, and rollback |
| Host files | `host-installation.py` | Plugin inventory, exact prior bytes, and active receipt |
| Reload mechanism | `reload_hosted.py`, `host_directory_exchange.py` | Compatibility admission and atomic directory swap |
| Host work | `HostedApplicationAdmission`, `HostedApplicationSettlement` | Semantic admission, preparation, and durable mutation settlement |
| Host effects | `ManagedHostBridge`, `IdeLifecycleApplication` | Exact application inspection, settlement, resume, and normal stop |
| Passive loaded identity | `PassiveApplicationHost` | Existing advertisement and exact process incarnation |
| Harness resources | `GuidanceInstallation` and provider adapters | Selected CLI guidance and explicit optional MCP |

The candidate coordinator holds `management.lock` across the composition.
The existing Control adapter also holds `activation.lock` during its filesystem segment.
The coordinator reuses `recovery/replacement/receipt.json`. It creates no second lifecycle journal.
The receipt binds the reservation, selected build, Host inventory, prior configuration, and phase.
Recovery uses that recorded selection. It does not resolve latest again.

Host effects also hold the existing profile-owned `host-install.lock`.
An exact hard link at `recovery/replacement/host-lock` reserves that inode across process interruption.
Ordinary Host installation and removal reject a reserved lock before replacement effects.
Terminal cleanup removes only the exact reservation link. Existing canonical lock files remain.
For restored prior absence, cleanup removes only the transaction-created canonical lock before the outer owner removes created profile directories.

## Commands and update policy

The candidate native executable exposes `kast versions`, `kast upgrade`, and `kast install`.
An unqualified upgrade obeys `TrackStable`, `TrackMain`, or `PinnedBuild` from the management receipt.
Exact version and commit selectors create a pin.
An unqualified upgrade of a pin rejects with `PINNED_BUILD` before effects.
The main revision, original build revision, and source tree remain distinct.

`kast install codex|copilot|pi` selects `CliOnly` by default.
`--mcp` explicitly selects `CliWithMcp`.
`kast install skill [--output <directory>]` exports the canonical skill without provider registration.
Provisioning outcomes describe provider resources. They do not establish core activation or an existing agent session's loaded guidance.
Core replacement can report `HARNESS_REFRESH_REQUIRED` when the selected integration bundle changes.

These commands describe the candidate contract.
They do not show that the current stable executable exposes that contract.
The candidate installer remains separate from the public bootstrap until the release owner completes rollout.

## Host identity and settlement

The application service captures build identity from bundled resources at plugin startup.
It separately captures the IDEA installation, profile, PID, process start, Host lifetime, and loaded auto-reload option.
Compatibility remains a distinct contract. Equal component versions do not prove compatibility.
The existing application endpoint publishes these facts without a project or Gradle import.

`SettleHost` fences new semantic work and preparation requests.
Settlement also examines native work and durable mutation records.
Unresolved mutation state keeps the Host fenced.
Client exit does not prove Host settlement.

`StopHost` requests ordinary IDEA exit through the application owner.
Its acknowledgment does not prove that the exact process incarnation exited.
The installer must prove process exit before ordinary replacement.
The private START adapter currently supports the selected default profile.
An unsupported custom-profile restart must reject before effects.

Process inspection applies known user and launcher exclusions before a native state probe.
A missing executable on a live process needs bounded operating-system evidence.
The shared process-state parser recognizes a zombie as exited.
An active or unavailable state cannot prove that an unknown process is unrelated.
Typed enumeration diagnostics record bounded counts on stderr without process payloads.

Legacy profile admission reads properties through explicit installer-only JCMD attachment.
An explicit configuration path overrides the default selector.
For supported macOS defaults, captured selector, vendor, home, and operating system establish the profile path.
The rule comes from `PathManager.getConfigDir`, `getDefaultConfigPathFor`, and `platformPath` in pinned Platform `262.9437.185` sources.
Unknown facts, configured `idea.paths.customizer` values, and independent plugin overrides reject.
Raw properties remain in memory and do not enter logs or receipts.

Hot reload needs the loaded auto-reload option and admitted descriptor, contract, and dependency layout.
Kast component JAR names remain stable. Third-party dependency names keep their versions.
The first migration from versioned Kast JAR names needs an ordinary restart.
Directory exchange and reload notifications do not establish activation.
Completion needs the expected loaded build, contract, and new Host lifetime.

Status keeps installed and loaded identities separate.
The native reader admits the selected IDEA metadata, profile, advertisement, PID, start time, and launcher.
It starts no Java process and sends no lifecycle request.
The saved IDEA selection supplies configuration authority. The payload manifest does not digest that mutable configuration file.
Host activation does not prove workspace readiness or semantic completion.

## Recovery meanings

| Terminal outcome | Necessary evidence | Exit |
| --- | --- | --- |
| `ACTIVATED` | Selected files and every affected running Host meet the selected build and contract. | Zero |
| `INSTALLED_NOT_RUNNING` | The planned stopped-IDE path completes and no affected IDEA instance runs. | Zero |
| `REJECTED` | Preparation rejects before retirement or active installation changes. | Nonzero |
| `ROLLED_BACK` | The exact prior composition or prior absence returns, with the required runtime and profile facts. | Nonzero |
| `RECOVERY_REQUIRED` | Settlement, activation, or restoration remains unproved. The fence and recovery evidence remain. | Nonzero |

A failed promised reload or restart cannot become `INSTALLED_NOT_RUNNING` merely because IDEA stopped.
After the fence exists, failure enters recovery.
Restoration preserves the prior update policy. Interrupted agent sessions stay stopped.

Private Control results `PREPARED`, `COMMITTED`, and `ABORTED` describe component stages only.
They do not establish a public terminal outcome.
`abort` accepts only a reservation without a managed composition and an exact preparation inventory.
It admits staged ownership, the exact manifest digest, complete payload bytes, and unchanged prior selection before deletion.
This proof does not establish the manifest's prepare-time file incarnation.
Unknown, missing, or corrupt evidence keeps recovery necessary.

Host preparation validates complete bytes before it publishes named candidate artifacts.
Synchronous failure removes only re-admitted artifacts from that call.
Ambiguous changed artifacts remain for recovery.
A process interruption can leave private staging entries in the journal. Unknown entries must not authorize broad cleanup.

Host rollback needs the prior directory and exact receipt preimage.
Missing preimages cannot become successful rollback.
Terminal cleanup admits current file facts before it accepts already-removed preimages on a repeated cleanup call.
The outer coordinator separately proves the required runtime state.
Neither file restoration nor cleanup alone proves that an old Host runs again.

## Focused evidence

The listed checks ran locally against the candidate working tree.
They prove the stated mechanism. They do not supply product-promotion evidence.

Public workspace lifecycle metadata shares its result serializer with private installer replies.
The installed projection uses exact local `$defs` for repeated runtime identity, lifecycle failure, and project target shapes.
The provider byte budget and qualification headroom remain unchanged.
Independent schema validation admits loaded identity and rejects a null process identifier.

| Check | Result | Scope |
| --- | --- | --- |
| `python3 packaging/test-host-installation.py` | PASSED, 62 cases | Host files, exact rollback, lock exclusion, real macOS swap, and preparation faults |
| `python3 packaging/test-installed-product-artifacts.py` | PASSED, 3 cases | Stable main JAR, duplicate rejection, embedded identity, and unchanged dependency bytes |
| `componentReleaseTest` | PASSED, 11 cases | Component release identity, exact stable main JAR, and archive collisions |
| `mise exec -- uv run --locked --script packaging/reload_hosted.py --self-test` | PASSED, 9 cases | Compatibility admission, layout rejection, and real directory swap |
| `:cli:test --tests '*ManagedControlAdapterTest'` | PASSED, 12 cases | Preparation, settlement prerequisite, commit, abort, and protected evidence |
| `:cli:test --tests '*ManagedControlCliTest'` | PASSED, 1 case | Closed component discriminators and exact encoded fields |
| `:cli:test --tests '*LifecycleProjectionTest'` | PASSED, 6 cases | Null and loaded identity shapes, local definitions, and invalid-identity rejection |
| `:cli:test --tests '*InstalledServerProjectionTest'` | PASSED, 11 cases | Provider schema byte budget and semantic projection contracts |
| `:cli:test --tests '*ManagedHostCliTest'` | PASSED, 6 cases | Scripted process facts and exact effect admission |
| `:cli:test --tests '*ManagedProcessCaptureTest'` | PASSED, 5 cases | Exclusions, unknown-process rejection, zombie policy, and bounded native state probe |
| `:distribution:contract:test --tests '*ObservedProcessStateTest'` | PASSED, 2 cases | Shared zombie, live, and malformed-state rules |
| `:cli:test --tests '*LegacyProfileSelectionTest'` | PASSED, 4 cases | Default profile authority and explicit-override rejection controls |
| `:distribution:cli:test --tests '*PassiveApplicationHostTest'` | PASSED, 4 cases | Passive identity, stale process, profile, and symlink rejection |
| `:app-server:test --tests '*IdeInstallationManagementTest'` | PASSED, 2 cases | Real local sockets and installer admission across distinct semantic contracts |
| `:runtime:hosted:hostedPlugin` | PASSED | ZIP assembly and configuration-cache-safe stable JAR names |
| `verifyJsonContracts` | PASSED | Syntax guard without new manual-contract allowances |

The Gradle selectors run through `mise exec -- ./gradlew` from the repository root.
The Host fixture suite reproduced preparation leaks before the correction.
After the correction, invalid payload, receipt flush, and record faults left no unrecorded artifacts.
A changed published receipt remained intact and returned `RECOVERY_REQUIRED`.

The real directory swaps prove filesystem behavior on this macOS host.
Scripted process facts do not prove native IDEA discovery, restart, legacy attachment, or normal quit.
Local socket cases prove protocol exchange. They do not prove IntelliJ lifecycle effects.
ZIP assembly does not prove that IDEA loads the plugin.

The installed-product harness also passed with existing `0.0.50001` artifacts from the preliminary product gate.
It admitted artifacts, staged installation, and removed Host files and SQLite state through native commands in an isolated home.
This run did not rebuild artifacts or establish source-bound product qualification.

Native installer inspection also passed against the selected running IDEA instance.
The captured process was PID `12733` with start time `1793323503343` milliseconds since the epoch.
The response bound the selected launcher, installation home, legacy Host lifetime, and default profile.
Its typed diagnostics reported `CAPTURED` and `ADMITTED` with `DEFAULT_MACOS`.
The retained frames are `inspect-admitted.stdout.json` and `inspect-admitted.stderr.txt` under `Documents/Codex/2026-10-30/managed-installation-native/` in the local user home.

This establishes process and profile admission. It does not establish activation, restart, reload, or semantic completion.

## Qualification and rollout gaps

This report supplies no live Host activation, semantic-query, restart, or restored-runtime evidence.
A1–A15 remain subject to the complete acceptance record and required repository guards.
The release owner must record the selected source, artifact digests, exact procedures, expected results, and actual results.
A required case does not count as complete if its result is SKIPPED or NOT RUN.

A16 remains NOT RUN because stable publication and public bootstrap activation remain pending in this report.
Do not advertise the candidate flow as the active public default.
After stable publication, the release owner must change the public bootstrap and do A16 through that exact entry.

Prose reports for this change reside under `build/reports/managed-installation-docs/` in the task checkout.
An `ASSESSED` result records measurements and glossary scope. It does not prove source truth or reader comprehension.
