---
type: System Explanation
title: Installation and managed files
description: Explains Control and Host installation ownership, managed receipts, payload
  admission, and endpoint identity.
tags:
- kast
- distribution
code_sources:
- path: install.sh
- path: packaging/install-checkout.sh
- path: packaging/install-local.sh
- path: packaging/host-installation.py
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationRecovery.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
- path: cli/src/test/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflowTest.kt
- path: packaging/test-host-installation.py
- path: packaging/test-installer-entrypoint.py
- path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
- path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/endpoint/InstalledEndpointAlias.kt
sources:
- id: openwiki-source-8f4d86f42434fcc1ce269879
  resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
- id: openwiki-source-f3718a29d9c6d868d0d9d73e
  resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
- id: openwiki-source-6fea3b3b86300de08664c4ee
  resource: repo://packaging/host-installation.py
generated:
  by: codex
  at: '2026-10-15T00:57:31.735Z'
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:59:49.803Z
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
An applied replacement coordinates prior retirement, saved configuration, recovery snapshots, payload installation, and service activation.

The managed root contains one ordinary `installation` directory.
The native public command and private transport payloads keep separate locations and owners.
`ManagedInstallationOwnedTree` admits exact destructive-recovery trees under the physical selected installation.
Its admission rejects aliases and symlink substitutions before deletion.
Unknown paths do not become owned because their names resemble Kast artifacts.

## Independent Host receipts

`host-installation.py` admits the Host release and plugin destination before effects.
The active receipt records the plugin root, destination, and artifact inventory with filesystem identities.
If the device identifier changes, admission keeps the recorded inode and owner constraints and consistently rebases recorded device identities.
The current root and destination must remain stable through the inventory examination.

Reinstall needs the exact current inventory.
Removal permits missing recorded entries, but each remaining entry must match the recorded artifact.
A foreign or changed file rejects ownership admission.
An absent plugin destination does not erase the receipt's root proof or permit deletion elsewhere.

## Recovery and evidence limits

Control recovery keeps the previous payload and qualified settings when replacement fails.
Failures during retirement, retention, activation, or recovery remain explicit.
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
