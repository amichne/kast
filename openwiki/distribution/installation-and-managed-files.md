---
type: "System Explanation"
title: "Installation and managed files"
description: "Explains Control and Host installation ownership, managed receipts, payload admission, and endpoint identity."
tags: [kast, distribution]

sources:
  - id: openwiki-source-5f9ee1677dd5352d4bd1273d
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationPublicEndpointSelection.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-6fea3b3b86300de08664c4ee
    resource: repo://packaging/host-installation.py
  - id: openwiki-source-fd4b4980f80cb901046d8f0e
    resource: repo://packaging/installation-lifecycle.py
  - id: openwiki-source-1380068036b00bec817a6579
    resource: repo://packaging/managed_installation.py
  - id: openwiki-source-b1af9cf4e2649975cc7bdf86
    resource: repo://packaging/managed_update_journal.py
  - id: openwiki-source-2a77c42fb15d42188a60711b
    resource: repo://packaging/test-host-installation.py
  - id: openwiki-source-a219ab80b9383d47a0037637
    resource: repo://packaging/test-installation-lifecycle.py
generated: { by: "codex", at: "2026-10-30T23:25:02.193Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-31T07:55:41.140Z
---



# Installation and managed files

## Select the product before effects

The public shell installer selects a published stable or developer channel.
Developer selection uses published channel evidence, independently of the checkout and installed version.
The checkout installer is a separate adapter for local development.
It admits the selected IDEA JBR before Gradle or an installed child JVM starts.

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
The managed composition coordinator owns Host settlement, replacement, and activation for a complete update.
Saved shared endpoints migrate to private settings. Explicit shared selections reject before effects.

The managed root contains one ordinary `installation` directory.
The native public command and private transport payloads keep separate locations and owners.
`ManagedInstallationOwnedTree` admits exact destructive-recovery trees under the physical selected installation.
Its admission rejects aliases and symlink substitutions before deletion.
Unknown paths do not become owned because their names resemble Kast artifacts.

## Managed composition transaction

`managed_installation.py` coordinates the existing Control and Host effect owners under the selected management lock.
It captures exact preimages for management, the public command, profile options, runtime state, and Host receipts.
The typed journal records the selected artifacts and one `STOPPED`, `HOT_RELOAD`, or `NORMAL_RESTART` strategy.

The coordinator fences public calls before Host settlement and payload replacement.
It holds the admitted Host lock during the Control and Host commit sequence.
A stopped Host stays stopped. A loaded Host needs the selected activation proof before terminal receipts and journal removal.
Recovery uses the recorded composition and preimages. An unresolved recovery keeps the fence and returns `RECOVERY_REQUIRED`.
A recovery-only invocation does not select a new release.

## Independent Host receipts

`host-installation.py` admits the Host release and plugin destination before effects.
The active receipt records the plugin root, destination, and artifact inventory with filesystem identities.
If the device identifier changes, admission keeps the recorded inode and owner constraints and consistently rebases recorded device identities.
The current root and destination must remain stable through the inventory examination.

Reinstall needs the exact current inventory.
Removal permits missing recorded entries, but each remaining entry must match the recorded artifact.
A foreign or changed file rejects ownership admission.
An absent plugin destination does not erase the receipt's root proof or permit deletion elsewhere.

Plan mode examines the existing target and recovery entries before Control installation changes.
Host installation and removal keep historical Control backups in `.kast-plugin-recovery`.
Host does not read or delete these backups.
This exception covers exact legacy baseline, detached, and replaced names with 32-character hexadecimal tokens.
Pending Host transactions, legacy installation candidates, and unknown retained names still reject the operation.
An active receipt still needs the exact plugin inventory before replacement.

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

## Source owners

- [install.sh](../../install.sh)
- [install-checkout.sh](../../packaging/install-checkout.sh)
- [install-local.sh](../../packaging/install-local.sh)
- [host-installation.py](../../packaging/host-installation.py)
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
`InstalledEndpointAlias` admits a physical directory and exact alias identities.
Its receipt keeps both target and alias identity so later consumers do not trust a fresh symbolic-link lookup.
