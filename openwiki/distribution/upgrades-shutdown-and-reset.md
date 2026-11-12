---
type: System Explanation
title: Upgrade, shutdown, and guarded reset
description: Explains managed product retirement, exact repair, vanished-root
  reclamation, retained legacy lifecycle contracts, and recovery evidence limits.
tags:
- kast
- distribution
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementShutdown.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt
- path: packaging/installation-lifecycle.py
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationResetTest.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRetirement.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRemoval.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
sources:
  - id: openwiki-source-977a0730acc279a32f98ae18
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
  - id: openwiki-source-c58b3f38ca46d44dd30a8771
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-cfc838a1a4168e12e43d463d
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationDisconnection.kt
  - id: openwiki-source-b30c7b16cf2f49971ca55950
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt
  - id: openwiki-source-8631d89978b8c4768c0c151b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceLifecycle.kt
  - id: openwiki-source-7cea1ff036129ab5e1cebfe3
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRemoval.kt
  - id: openwiki-source-e72662b6389b5cd2f0412448
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRetirement.kt
  - id: openwiki-source-a62e12ff14ead337deede61b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
  - id: openwiki-source-7391bd7fab188bde9fd1c847
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ProviderRegistration.kt
  - id: openwiki-source-dfb88490277ececa651be1f8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - id: openwiki-source-3ca2bb7453e21d39c96827be
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RecordedRecoveryInstaller.kt
  - id: openwiki-source-d258f44cd873e00e544ca7ed
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
  - id: openwiki-source-d9ace9189c53b7c82c5ed60c
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
  - id: openwiki-source-6fea3b3b86300de08664c4ee
    resource: repo://packaging/host-installation.py
  - id: openwiki-source-5a685931788e905bc230c148
    resource: repo://packaging/installation-recovery.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-5e407ffc0053b191a3e609bc
    resource: repo://packaging/managed_update_contract.py
  - id: openwiki-source-988817829b83f3eaab1d41af
    resource: repo://packaging/test-managed-installation.py
  - id: openwiki-source-8fae9d27fc5ee242f935c4cf
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostBundleRefresh.kt
  - id: openwiki-source-5bce5e00d8d37b48b461a27e
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedApplicationSettlement.kt
  - id: openwiki-source-64703967945cd84894f27ab4
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostNativeSettlement.kt
  - id: openwiki-source-4d9e0980fb11127dc7427b28
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/IdeLifecycleStop.kt
  - id: openwiki-source-93a63f2900e2bb9b08843608
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/LifecycleReply.kt
generated: { by: "amp", at: "2026-11-12T22:31:48.032Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-12T22:50:57.382Z
---

# Upgrade, shutdown, and guarded reset

## Managed product retirement

Public upgrade selects one complete product; exact repair reuses its recorded selection rather than a fresh channel result.
`ManagedWorkspaceRetirement` admits every workspace generation and acquires exclusive query leases before writing the product fence or retiring a Host.
Each successful retirement records the exact generation. Busy or unreadable state rejects before unproved retirement effects.
Ordinary scoped `stop` uses the same generation retirement without a product fence or persistent disablement.

After product retirement, and during preparation admission, `WorkspaceReclamation` inspects vanished roots.
Cleanup requires proven root absence, no query lease, exact retired generation evidence, and settled private `.kast` state.
The owner atomically detaches the private profile and retains an exterior `.removing-ID.json` receipt until deletion completes.
Changed identities, reappearing roots, and unrecognized private evidence retain recovery state.
Repository content, shared runtime bytes, and external Gradle homes are outside this profile deletion boundary.

Full product removal retains its recovery interpreter and payload inventory under `recovery/installation/product-removal`.
`UninstallManagedRoot` admits that complete import tree before recovery execution and checks remaining files before cleanup.
These source contracts do not establish native removal qualification.
Full removal now delegates receipted Pi guidance cleanup to `IntegrationDisconnection`, preserving foreign siblings.
It removes recorded CODEX/COPILOT resources before Host retirement, using provider inventories rather than routing records as ownership proof.
A provider conflict preserves the Host. The registration lock protects a final empty-record check through payload removal.
Legacy `uninstall --force [--json]` holds an exterior workspace lifetime lease before detachment.
Either managed store or `product-selection.json` rejects with `MANAGED_STATE_REQUIRES_UNINSTALL`; managed products use ordinary uninstall with its recovery journal.
These source-level removal boundaries still need full native qualification.

## Admit the existing lifecycle first

The following coordinator, legacy attachment, and hot-reload contracts remain for existing lifecycle and recovery owners.
They do not expose a public desktop, Control-only, or force-reinstall route.
`InstalledDaemonUpgrade` examines the selected service before an update request.
It distinguishes an absent daemon, pending blockers, an exact sealed permit, an already-committed permit, and rejection.
The permit keeps the selected daemon target and request identity through commit or cancellation.
Uncertain service markers do not become an absent-service success.

The daemon upgrade gate coordinates management, sessions, and invocation admission.
An installer cannot independently retire the process while another lifecycle owner still admits work.
Control-only replacement also needs current compatible Host evidence.
The replacement path preserves the prior Control when its preflight cannot establish that evidence.

## Shutdown and recovery

Shutdown fences new tool calls and automatic restart before it waits for owned work to settle.
Recorded process identity scopes the permitted retirement effects.
An in-flight mutation needs its existing recovery settlement before the workspace lane ends.
Shared IDEA and Gradle processes remain outside Kast's force-retirement ownership.

After an acknowledged normal stop, the coordinator waits within the existing Host settlement budget for a captured process inventory proving absence.
During that poll, `PROCESS_IDENTITY_UNAVAILABLE` produces `WAITING` evidence and does not prove that the process stopped.
If the deadline expires, the timeout retains that native failure code.
A changed process, a Host identity rejection, or cancellation ends the wait without admitting absence.

Installation recovery preserves the failure stage and the result of restoration.
A failed payload completion keeps the shutdown fence and its exact failure evidence.
Uncertain retirement or retained storage remains visible for the next recovery decision.

## Recovery precedes release selection

`ManagedUpgrade` examines pending replacement records and the shutdown fence before policy or release resolution.
A pending transaction invokes the admitted installed bridge with `--recover-only` and the selected output options.
If installed admission fails, `RecordedRecoveryInstaller` admits only the candidate identity from the replacement receipt.
Admission binds canonical paths, the current owner, manifest digest, exact payload inventory, file modes, and installer capability.
The candidate can occupy its recorded stage, the installation directory, or the journal quarantine.
Legacy version directories never supply this recovery capability.
This capability invokes only `--recover-only` without release access or fresh selection.
Rejected admission returns finite `RECOVERY_REQUIRED` without a child.
An uncertain child invocation never triggers another recovery invocation.
The wrapper returns the recovery child's terminal result and does not continue into a new selection.

The outer recovery owner reads the captured journal and exact prior composition.
It resumes terminal finalization or restores the recorded preimages under the selected locks.
A missing bridge can use only the recorded release and digest for recovery.
An unresolved active transaction keeps its recovery evidence and existing fence.

Recovery first examines a reservation without a composition.
The owner admits the exact staged Control, its complete inventory, and the unchanged prior installation or absence.
Host artifacts and retirement fences reject this preparation-only path.
A successful abort returns `REJECTED` with `CANCELLED`.
Unknown Host artifacts and changed payloads preserve recovery evidence.

After terminal proof, the owner renames the journal to a completed slot through a no-replace operation.
This atomic move removes the pending path before it deletes the receipt.

Before recursive deletion, the Control owner also moves each admitted retained payload outside the pending transaction.
Each destination binds the transaction UUID and source path through a no-replace operation.
An interruption during deletion leaves detached storage without a partial inventory that can block recovery admission.

A later cleanup failure emits `kast_managed_storage_cleanup` with `FAILED` and its finite reason.
The remaining closed storage does not block another upgrade.
A pending journal path still blocks public calls, including an empty directory or dangling symbolic link.

Ordinary upgrade preserves owned settings and history. It does not use force reset to bypass failed ownership admission.
The exceptional reset path remains separate from the recorded transaction.

## Re-admit legacy Host evidence

Legacy inspection keeps the attached profile proof with its exact process incarnation and opaque Host identity.
Re-admission compares the PID, start time, executable, Host identity, IDEA home, IDEA build, and complete compatibility digest.
A matching response keeps the earlier profile proof.
A changed or missing captured fact rejects as a concurrent change.
The opaque legacy identity remains separate from the runtime identity contract.

Without a captured endpoint, re-admission requests explicit `ATTACH` and compares the admitted snapshot.
Bare `REJECT_UNPROVEN` needs the earlier attached proof before it can preserve profile authority.
The coordinator repeats admission under its management lock before preparation and before the fence.
A running legacy Host without restart permission rejects before journal creation or retirement.

## Request a scoped VFS refresh

Hot reload needs compatible archives, enabled native auto reload, and the live `HOST_PREPARED_RELOAD_REFRESH` capability.
`MANAGED_HEADLESS` identity parsing rejects enabled auto reload.
This mode uses the existing settlement and normal-stop path.
`SETTLE_RELOAD` prepares the exact cached `lib/kast-ide-hosted.jar` before file exchange.
The checked acknowledgment keeps the Host, transaction, and prepared marker together.
Ordinary `SETTLE` remains independent of reload preparation and supports normal stop.

After exchange, the Host-file owner advances only the modification time of the admitted main JAR under its existing lease.
It preserves the file's inode, owner, bytes, mode, and access time.
The coordinator then requests `REFRESH` for that prepared transaction.
The reply owner dispatches the SDK effect only after the acknowledgment frame writes successfully.
The SDK dirties the exact prepared marker and records bounded content-event counts.
Only a fresh selected runtime handshake establishes activation.

Only `WRONG_HOST`, `HOST_UNAVAILABLE`, `PLUGIN_UNAVAILABLE`, and `EXCHANGE_DEADLINE_EXCEEDED` can defer refresh rejection to the existing bounded runtime wait.
That wait still needs exact selected build, contract, process, and Host lifetime evidence.
The caller sends one refresh request.

## Settle the actual Host during rollback

Hot-reload recovery first admits the actual Host as the recorded prior or selected build.
It settles that Host before the rollback exchange.
A loaded selected build needs `SETTLE_RELOAD` and a modification-time update after restoration.
If the selected build remains loaded, recovery restores prior bytes and requests a refresh from that settled Host.
Recovery then needs the prior build and a fresh Host lifetime.
If the original Host remains loaded, recovery restores bytes and resumes that same held Host.
Resume targets the held lifetime, including a fresh Host with the prior build.

Unknown builds, changed processes, and unproved restoration keep `RECOVERY_REQUIRED` fenced.

## Retained reset has separate proofs

Force reset bypasses old payload and receipt admission for the selected deletion scope.
It still needs a canonical physical root, an exterior fence, a stable lock, and qualified process retirement.
HOME, filesystem roots, and ancestors of HOME reject scope admission.
An ancestor symlink cannot redirect the admitted scope to protected storage.

The retained typed transitions carry fenced, quiescent, and erased facts into the next legal effect.
The former staged/installed reset states and force-reinstallation completion owner are gone.
Legacy force uninstall remains separate from ordinary managed removal with a journal.

Lifecycle adapter tests establish these decisions and effect orders under controlled process outcomes.
Actual launchd retirement and IDEA restart behavior need their separate native procedures.

## Focused tests

- [ForceInstallationResetTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationResetTest.kt)
- [test-managed-installation.py](../../packaging/test-managed-installation.py)
- [HostRefreshRpcTest.kt](../../runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostRefreshRpcTest.kt)

## Source owners

- [InstalledDaemonUpgrade.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt)
- [DaemonUpgradeGate.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt)
- [ForceInstallationReset.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt)
- [ForceLifecycleStates.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt)
- [ManagementShutdown.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementShutdown.kt)
- [InstallationDaemonUpgrade.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt)
- [ControlInstallationRecovery.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt)
- [installation-lifecycle.py](../../packaging/installation-lifecycle.py)

## Read next

- [Installation recovery](installation-and-managed-files.md)
- [Invocation settlement](../transport/invocations-and-durable-records.md)
- [Management](management-and-connections.md)
