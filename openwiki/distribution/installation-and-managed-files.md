---
type: "System Explanation"
title: "Installation and managed files"
description: "Explains Control and Host installation ownership, managed receipts, payload admission, and endpoint identity."
tags: [kast, distribution]

sources:
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-19c1ebaf58581a8fc0c5c10a
    resource: repo://cli/build.gradle.kts
  - id: openwiki-source-a3af3d4f4fd16f9ce429f3c5
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationManifest.kt
  - id: openwiki-source-5f9ee1677dd5352d4bd1273d
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationPublicEndpointSelection.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-f2c689f11ca37c5559bce314
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastNativeMain.kt
  - id: openwiki-source-c4c5adde2b0966463aa6381b
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/OneShotInvocationRecord.kt
  - id: openwiki-source-689991f3a476668d88b8c28b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicQueryDispatch.kt
  - id: openwiki-source-d1f8b0a2ac011ef2d36005a5
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-03ffc32a0ca502ab67c54b25
    resource: repo://install.sh
  - id: openwiki-source-069607880d661efb9c5fbc84
    resource: repo://packaging/host_directory_exchange.py
  - id: openwiki-source-6fea3b3b86300de08664c4ee
    resource: repo://packaging/host-installation.py
  - id: openwiki-source-ab52e7c01cf17ee137f833e7
    resource: repo://packaging/immutable_store.py
  - id: openwiki-source-fd4b4980f80cb901046d8f0e
    resource: repo://packaging/installation-lifecycle.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-7f5792fd36f508c6577b3562
    resource: repo://packaging/managed_product_installation.py
  - id: openwiki-source-0bc40485351c24e0087737b7
    resource: repo://packaging/managed_product_removal.py
  - id: openwiki-source-a4a155ae2c50f22d2a80ceaa
    resource: repo://packaging/managed_release.py
  - id: openwiki-source-b1af9cf4e2649975cc7bdf86
    resource: repo://packaging/managed_update_journal.py
  - id: openwiki-source-608c1b34f50deb259c322e04
    resource: repo://packaging/product_mcp_removal.py
  - id: openwiki-source-a0c37a49715759f5ea160f59
    resource: repo://packaging/product_removal_recovery.py
  - id: openwiki-source-2a77c42fb15d42188a60711b
    resource: repo://packaging/test-host-installation.py
  - id: openwiki-source-a219ab80b9383d47a0037637
    resource: repo://packaging/test-installation-lifecycle.py
  - id: openwiki-source-b3ca2d66028c874ac7b10e77
    resource: repo://packaging/test-managed-build-record.py
  - id: openwiki-source-988817829b83f3eaab1d41af
    resource: repo://packaging/test-managed-installation.py
generated: { by: "amp", at: "2026-11-13T02:07:25.535Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T03:20:11.225Z
---



# Installation and managed files

## Select the product before effects

The public installer selects one complete product from stable or preview release evidence.
The product record binds both supported targets to exact component and native qualification identities.
Selection is independent of the checkout and installed version.
A retained legacy component pair cannot satisfy complete-product admission.
Installed Control launchers need no user JVM, IDEA installation, Python, or uv.
Checkout assembly remains a separate developer boundary, not a product prerequisite.

Control owns the management executable, coordinator, adapters, private launchers, and installed configuration.
Host owns the IDEA plugin and semantic runtime.
Their release versions are independent.
Exact hosted contract identity and platform admission establish compatibility between them.
Version equality alone does not establish that compatibility.

## Control replacement

`ControlInstallationInput` admits the selected HOME and installation locations.
`InstallationWorkflow` examines the request and payload inventory before replacement.
Plan mode reports the admitted plan without activation effects.
An applied replacement coordinates prior retirement, saved configuration, recovery snapshots, and payload installation.

The private Control workflow reports payload installation without service activation.
Its retained composition coordinator owns legacy Host settlement, replacement, and activation.
Complete-product activation uses the product journal path described below.
Saved shared endpoints migrate to private settings. Explicit shared selections reject before effects.

The managed root contains one ordinary `installation` directory.
The `cli` module produces one native Control executable at `share/kast/libexec/kast-management`.
Thin launchers select management, query, MCP, tool RPC, service, daemon, Codex, or installer entry points in that executable.
The Control archive contains no `lib` directory, JARs, or class files.
The producer guard rejects a second native image and compares the canonical executable with its qualified producer.
It also rejects retired task payloads.
Published-release admission keeps support for the historical pair of native executables when the new layout marker is absent.
`ManagedInstallationOwnedTree` admits exact destructive-recovery trees under the physical selected installation.
Its admission rejects aliases and symlink substitutions before deletion.
Unknown paths do not become owned because their names resemble Kast artifacts.

## Product activation and retained composition recovery

`managed_installation.py` routes a newly admitted product to `managed_product_installation.activate`.
That owner publishes verified runtime bytes and their separate immutable vendor base.
It stages Control, saves exact management and command preimages, and publishes the existing replacement journal only after those records are durable.
Recovery admits the recorded selection and retires the prior installation before moving payloads.
The replacement receipt preserves provider registrations and recorded guidance.
`ProductInstalled` reports `NOT_STARTED`; payload activation does not prove a loaded Host or workspace readiness.
The coordinator then refreshes installed guidance and retains finite refresh notices.

The installation manifest includes `codexHome` as the selected HOME's `.codex` path.
The Kotlin client requires that field; an installer-only manifest check cannot prove client admission.

Product activation also writes the `-complete` client launchers and the pinned `config/environment` file before recording the payload inventory.
The launchers preserve arguments and select the raw native launchers.
Fresh configuration uses catalogue defaults; replacement preserves the saved bytes.
Configuration remains mutable owner input outside the immutable payload inventory.
Recovery execution retains only the immutable import tree and manifest, not configuration or invocation state.
Unexpected configuration entries and linked files reject removal.

Successful native calls leave empty directories under `state/run/one-shot` or `state/run/tool-sessions`.
Replacement and uninstall admit those exact empty directories, but reject surviving invocation records and unknown directory names.
Removal rechecks directory identities and uses empty-directory deletion, never record deletion, for this bookkeeping.
Bounded `kast-invocation-state` observations distinguish admitted state from unknown directories and unsettled invocations.

Control staging cleanup retains the directory identity before extraction.
A failed preparation deletes only that allocation, while no replacement journal owns it.
Runtime and base publication stages retain original evidence before copying payload bytes.
Control, unpublished journal, runtime, and base stages live outside the managed root and stores.
A crash before receipt creation therefore cannot obstruct uninstall.
Interrupted exterior stages can remain on disk; uninstall does not infer ownership from their names or sweep them.
Recovery of older in-store stages checks surviving files, links, and directory identities before cleanup.
Unknown legacy contents or an incomplete archive without an independently verified replacement remain protected.

Repair stages verified replacement bytes before retirement, without modifying a damaged selected runtime or base.
The existing replacement journal binds those bytes to the damaged directory identity and original qualification.
After exact Host retirement and fence admission, repair deletes that owned directory and publishes the replacement.
Interrupted deletion or publication resumes through the same journal.
Foreign additions, changed links, and replaced directory identities block deletion.
Workspace roots and external Gradle homes remain outside this reset.
Journal staging and the destination store must share a filesystem; repair rejects a mismatch before retirement or deletion.

The retained composition recovery path coordinates the existing Control and Host effect owners under the selected management lock.
The bootstrap disables Python bytecode writes before importing its installed helpers.
Those imports preserve the exact installed payload inventory.
The legacy composition journal captures exact preimages for management, the public command, profile options, runtime state, and Host receipts.
The typed journal records the selected artifacts and one `STOPPED`, `HOT_RELOAD`, or `NORMAL_RESTART` strategy.
These retained strategies do not restore a public desktop or archive selection route.

IDEA metadata admission accepts a regular canonical file owned by the current user or root.
It rejects group-write permissions, world-write permissions, symbolic links, and more than one MiB of metadata.
Managed records still need the current user as their owner.

The coordinator fences public calls before Host settlement and payload replacement.
It holds the admitted Host lock during the Control and Host commit sequence.
A stopped Host stays stopped. A loaded Host needs the selected activation proof before terminal receipts and journal removal.
Recovery uses the recorded composition and preimages. An unresolved recovery keeps the fence and returns `RECOVERY_REQUIRED`.
A recovery-only invocation does not select a new release.

## Retained independent Host receipts

`host-installation.py` admits the Host release and plugin destination before effects.
The active receipt records the plugin root, destination, and artifact inventory with filesystem identities.
If the device identifier changes, admission keeps the recorded inode and owner constraints and keeps a consistent mapping from recorded device identities to their current identities.
The current root and destination must remain stable through the inventory examination.

Managed Host replacement needs the exact recorded inventory.
Removal permits missing recorded entries, but each remaining entry must match the recorded artifact.
A foreign or changed file rejects ownership admission.
An absent plugin destination does not erase the receipt's root proof or permit deletion elsewhere.

Host preflight examines the existing target before Control installation changes.
Ordinary Host installation keeps legacy Control backups and unselected recovery entries in `.kast-plugin-recovery`.
Host removal keeps those backups and rejects pending Host transactions and unknown recovery entries.
Legacy backup names include baseline, detached, and replaced names with 32-character hexadecimal tokens.
Force installation can remove entries with known recovery names. It unlinks symbolic links without traversing their targets.

A force dry run accepts a user-owned recovery directory with mode `0755` and keeps that mode.
Force installation restores mode `0700` under the Host lock.
Both force modes reject a group-writable recovery directory.

Terminal Host cleanup first admits the selected or restored files and their active receipt.
The Host owner then moves each admitted candidate or prior payload outside the pending journal before recursive deletion.
A no-replace native move preserves foreign destinations, including empty directories.
Partial deletion cannot block the next terminal admission or release of the reserved Host lock.
Cleanup failures emit `kast_managed_host_cleanup` with `FAILED` and a finite reason.

## Recovery and evidence limits

Control recovery keeps the previous payload and qualified settings when replacement fails.
Failures during retirement, retention, activation, or recovery remain explicit.

Without a workspace registry, retirement admits only a stopped coordinator with exact empty stores and private ownership.
Current thread and invocation stores need their exact layout versions, empty record directories, and empty lock files.
The protocol cache must stay empty, and desktop discovery can contain only its empty lock file.
Durable records, unknown artifacts, live readiness evidence, symlinks, and foreign ownership still block retirement.

Host receipt tests establish selected-target and artifact protection under private files.
They do not establish that a running IDEA process loaded the new Host.
Read native lifecycle evidence before a claim about loaded components or workspace semantics.

## Focused tests

- [InstallationWorkflowTest.kt](../../cli/src/test/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflowTest.kt)
- [test-host-installation.py](../../packaging/test-host-installation.py)
- [test-installer-entrypoint.py](../../packaging/test-installer-entrypoint.py)
- [test-managed-installation.py](../../packaging/test-managed-installation.py)

## Source owners

- [install.sh](../../install.sh)
- [install-checkout.sh](../../packaging/install-checkout.sh)
- [install-local.sh](../../packaging/install-local.sh)
- [host-installation.py](../../packaging/host-installation.py)
- [managed_product_installation.py](../../packaging/managed_product_installation.py)
- [managed_installation.py](../../packaging/managed_installation.py)
- [managed_release.py](../../packaging/managed_release.py)
- [InstallationWorkflow.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt)
- [ControlInstallationInput.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt)
- [ControlInstallationRecovery.kt](../../cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt)
- [ManagedInstallationOwnedTree.kt](../../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt)
- [ControlPayloadInventory.kt](../../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt)

## Read next

- [Management connections](management-and-connections.md)
- [Guarded lifecycle](upgrades-shutdown-and-reset.md)
- [Release provenance](release-candidates-and-promotion.md)

## Shared limits and endpoint aliases

`ControlDistributionLimits` supplies shared entry, payload, and manifest limits across installation and runtime admission.
A limit does not prove archive ownership or content identity.
Archive admission permits at most 16,384 entries.
Installed payload admission permits at most 32,768 entries because the pinned private interpreter adds files after archive extraction.
Payload-byte and manifest-byte bounds remain separate from those entry budgets.
`InstalledEndpointAlias` admits a physical directory and exact alias identities.
Its receipt keeps both target and alias identity so later consumers do not trust a fresh symbolic-link lookup.
