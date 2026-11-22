---
type: System Explanation
title: Upgrade, shutdown, and guarded reset
description: Explains managed product retirement, exact repair, vanished-root
  reclamation, retained lifecycle contracts, and recovery evidence limits.
tags:
- kast
- distribution
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationResetTest.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRetirement.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceRemoval.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ProductRemovalRecoveryTest.kt
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
- path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt
- path: packaging/internal/installation/installation-lifecycle.py
- path: packaging/internal/installation/installation-recovery.py
sources:
  - id: openwiki-source-977a0730acc279a32f98ae18
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
  - id: openwiki-source-c58b3f38ca46d44dd30a8771
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/direct/InstalledToolAdmission.kt
  - id: openwiki-source-e4a9595c544ec2b876590037
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/BuildCatalog.kt
  - id: openwiki-source-dc8df2b070b8c6ba7da7a8f9
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStore.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-4a775e59f7cd3b1cc2d7cc10
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InstallationLifecycle.kt
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
  - id: openwiki-source-dfb88490277ececa651be1f8
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicInstallationReset.kt
  - id: openwiki-source-3ca2bb7453e21d39c96827be
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RecordedRecoveryInstaller.kt
  - id: openwiki-source-e62300b4b70eef650b59f1db
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/RejectedWorkspaceRemoval.kt
  - id: openwiki-source-d258f44cd873e00e544ca7ed
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallManagedRoot.kt
  - id: openwiki-source-47ff7393e051378d9c971323
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UninstallRetirementJournal.kt
  - id: openwiki-source-76b7f82a658d043d4483702c
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/UserStateInventoryBuilder.kt
  - id: openwiki-source-d9ace9189c53b7c82c5ed60c
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/WorkspaceReclamation.kt
  - id: openwiki-source-8a98987a84aecf7454a62390
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ProductRemovalRecoveryTest.kt
  - id: openwiki-source-a683fe18522ef2e7acd1dca0
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/RecordedRecoveryInstallerTest.kt
  - id: openwiki-source-ebaa784f87c513520ea30215
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/UninstallContractTest.kt
  - id: openwiki-source-d1f8b0a2ac011ef2d36005a5
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
  - id: openwiki-source-532b679718b686c6f5550473
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/InstallationReplacementReceipt.kt
  - id: openwiki-source-553e99bfbd3574888348e9fa
    resource: repo://distribution/contract/src/main/resources/management/uninstall-retirement.schema.json
  - id: openwiki-source-ce3f1fc4b6e5a2d079d0daf2
    resource: repo://distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/InstallationReplacementReceiptTest.kt
  - id: openwiki-source-f819c9a16e09783f5c943c2d
    resource: repo://packaging/internal/installation/immutable_store.py
  - id: openwiki-source-e2f98663750a2e6ad150484d
    resource: repo://packaging/internal/installation/installation-lifecycle.py
  - id: openwiki-source-8a0fac4a3d7fbab0f56c743e
    resource: repo://packaging/internal/installation/installation-recovery.py
  - id: openwiki-source-9f2a730e7185b57c14ac08e8
    resource: repo://packaging/internal/installation/managed_installation.py
  - id: openwiki-source-07f32471b5605b546192361f
    resource: repo://packaging/internal/product/managed_product_installation.py
  - id: openwiki-source-558cf2932a7142fbf6311f57
    resource: repo://packaging/internal/product/product_removal_recovery.py
  - id: openwiki-source-678716bd23af50e8bbe1978b
    resource: repo://packaging/internal/product/product_state_removal.py
  - id: openwiki-source-ac80bd41e2d128ed2647c9d8
    resource: repo://packaging/tests/test-installation-recovery.py
  - id: openwiki-source-abab1b24f64b3271633d720d
    resource: repo://packaging/tests/test-managed-build-record.py
generated: { by: "codex", at: "2026-11-22T20:53:36.158Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
---

# Upgrade, shutdown, and guarded reset

## Managed product retirement

Public upgrade selects one complete product; exact repair reuses its recorded selection rather than a fresh channel result.
`ManagedWorkspaceRetirement` admits every workspace generation and acquires exclusive query leases before writing the product fence or retiring a Host.
Each successful retirement records the exact generation. Busy or unreadable state rejects before unproved retirement effects.
Ordinary scoped `stop` uses the same generation retirement without a product fence or persistent disablement.

Uninstall checks rejected workspace entries before fencing neighboring Hosts.
Its preparation scope removes only the exact fence created by that attempt when no uninstall journal was published.
An existing fence, a changed fence, or a committed journal prevents that cleanup.
Inactive rejected records have a separate uninstall admission under exclusive entry access; uncertain live state still rejects removal.
The uninstall retirement journal requires the admitted product identity.
Decoding a journal without it fails at `uninstall-retirement` with "retirement proof lacks its product identity", and the schema requires a closed product reference.

Exact repair stages verified runtime and base bytes in the existing replacement journal.
Only recorded Host retirement and the unchanged shutdown fence permit deletion of a damaged owned runtime directory.
The same journal resumes an interrupted reset before Control replacement.
Foreign files, changed links, and replacement directory identities remain protected.

Retained local product records use installed-record validation, not published-only decoding.
`managed_product_installation.reference_at` keeps the pinned local selection and exact record, target and artifact identities.
`immutable_store` uses that same installed-record boundary for original evidence, reset, publication and retained inventory.
`UninstallManagedRoot` also admits local schema 4 records when checking retained immutable evidence.
This preserves recovery eligibility without treating a local build as a published two-target release or weakening deletion ownership.

After product retirement, and during preparation admission, `WorkspaceReclamation` inspects vanished roots.
Cleanup requires proven root absence, no query lease, exact retired generation evidence, and settled private `.kast` state.
The owner atomically detaches the private profile and retains an exterior `.removing-ID.json` receipt until deletion completes.
Changed identities, reappearing roots, and unrecognized private evidence retain recovery state.
Repository content, shared runtime bytes, and external Gradle homes are outside this profile deletion boundary.

Dependency sharing does not change existing workspace records during upgrade, repair, or generation rebuild.
Only first creation attempts index seeding. Removing one workspace leaves the shared Gradle cache available to other workspaces.
After workspace removal, full uninstall admits only the fixed Kast-owned `kast-workspaces/shared/gradle` cache for deletion.
It refuses foreign siblings or owners, `.kast` recovery evidence, and trees exceeding the separate 2,000,000-entry cache budget.
Cleanup does not follow links into external targets. An explicitly selected external Gradle home remains outside removal authority.
These are source-level ownership rules, not completed native removal qualification.

Full product removal retains its recovery interpreter and payload inventory under `recovery/installation/product-removal`.
`UninstallManagedRoot` admits that complete import tree before recovery execution and checks remaining files before cleanup.
Retained recovery traversal uses the 32,768-entry installed-tree budget, including the private interpreter.
The separate archive-admission budget remains 16,384 entries.
The retained import tree excludes saved configuration and mutable invocation state.
Completed native calls may leave empty `state/run/one-shot` and `state/run/tool-sessions` directories.
Replacement admits that empty bookkeeping, and removal deletes it with identity checks and empty-directory operations.
Surviving invocation records, unknown directory names, and linked directories still reject before payload removal.
These source contracts do not establish native removal qualification.
Uninstall changes no agent configuration. Besides the managed root and its workspace store, it removes only admitted Kast-owned state under `~/.kast`.
Legacy `uninstall --force [--json]` holds an exterior workspace lifetime lease before detachment.
Either managed store or `product-selection.json` rejects with `MANAGED_STATE_REQUIRES_UNINSTALL`; managed products use ordinary uninstall with its recovery journal.
These source-level removal boundaries still need full native qualification.

## Admit the existing lifecycle first

The following coordinator contracts remain for existing lifecycle and recovery owners.
They do not expose a public Control-only or force-reinstall route.
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

Installation recovery preserves the failure stage and the result of restoration.
A failed payload completion keeps the shutdown fence and its exact failure evidence.
Uncertain retirement or retained storage remains visible for the next recovery decision.

Detached Control admission emits bounded payload-admission evidence for acceptance or rejection.
A payload limit rejection retains `CONTROL_LIMIT_EXCEEDED` and its resource budget instead of becoming an ownership failure.
The replacement adapter preserves that rejection as `CONTROL_REJECTED` with `CONTROL_LIMIT`.

## Recovery precedes release selection

`ManagedUpgrade` examines pending replacement records and the shutdown fence before policy or release resolution.
A pending transaction invokes the admitted installed bridge with `--recover-only` and the selected output options.
Installed bridge admission requires unique manifest paths, an exact complete Control inventory, and verified payload bytes.
If installed admission fails, `RecordedRecoveryInstaller` admits only the candidate identity from the replacement receipt.
It strictly decodes `recovery/replacement/receipt.json` as `InstallationReplacementReceipt`, the product installer's journal.
That contract requires the reservation and the product record, models file preimages as closed `ABSENT` or `PRESENT` variants, and records the closed phase set, selection and previous manifest digests, and the shutdown fence.
Its `composition` field admits only `null`, because only a product journal is recoverable.
Admission binds canonical paths, the current owner, manifest digest, exact payload inventory, file modes, and installer capability.
The candidate can occupy its recorded stage, the installation directory, or the journal quarantine.
New exterior staging must match the recorded transaction UUID exactly; legacy interior staging remains admitted.
Legacy version directories never supply this recovery capability.
This capability invokes only `--recover-only` without release access or fresh selection.
Rejected admission returns finite `RECOVERY_REQUIRED` without a child.
An uncertain child invocation never triggers another recovery invocation.
The wrapper returns the recovery child's terminal result and does not continue into a new selection.

The Python coordinator resumes only a recorded product journal, through `managed_product_installation.resume`.
It resumes terminal finalization or restores the recorded preimages under the selected locks.
Any other recorded journal, including a legacy composition record, returns `RECOVERY_REQUIRED` with `RECOVERY_UNPROVEN`.
A missing bridge can use only the recorded release and digest for recovery.
An unresolved active transaction keeps its recovery evidence and existing fence.

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
- [test-managed-installation.py](../../packaging/tests/test-managed-installation.py)
- [test-installation-recovery.py](../../packaging/tests/test-installation-recovery.py)

## Source owners

- [InstalledDaemonUpgrade.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt)
- [DaemonUpgradeGate.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/DaemonUpgradeGate.kt)
- [ForceInstallationReset.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt)
- [ForceLifecycleStates.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt)
- [ManagementUninstall.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementUninstall.kt)
- [ManagedUpgrade.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedUpgrade.kt)
- [installation-lifecycle.py](../../packaging/internal/installation/installation-lifecycle.py)
- [installation-recovery.py](../../packaging/internal/installation/installation-recovery.py)

## Read next

- [Installation recovery](installation-and-managed-files.md)
- [Invocation settlement](../transport/invocations-and-durable-records.md)
- [Management](management-and-connections.md)
