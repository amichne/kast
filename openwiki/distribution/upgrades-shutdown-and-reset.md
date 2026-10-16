---
type: System Explanation
title: Upgrade, shutdown, and guarded reset
description: Explains exact update permits, shutdown fencing, guarded force-reset
  transitions, payload completion, and recovery when lifecycle effects fail.
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
- path: distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceReinstallationTest.kt
sources:
  - id: openwiki-source-977a0730acc279a32f98ae18
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledDaemonUpgrade.kt
  - id: openwiki-source-bae14a3ed4bc7ba21f443356
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationReset.kt
  - id: openwiki-source-6f0b647814adafaaa5d5c5f2
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
  - id: openwiki-source-9c24982d1beb56645cb4eb23
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetFence.kt
  - id: openwiki-source-5eb124d96b8eb114e88aabe7
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceResetOutcome.kt
generated: { by: "codex", at: "2026-10-16T06:54:44.448Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T06:54:44.448Z
---

# Upgrade, shutdown, and guarded reset

## Admit the existing lifecycle first

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

## Force reset has separate proofs

Force reset bypasses old payload and receipt admission for the selected deletion scope.
It still needs a canonical physical root, an exterior fence, a stable lock, and qualified process retirement.
HOME, filesystem roots, and ancestors of HOME reject scope admission.
An ancestor symlink cannot redirect the admitted scope to protected storage.

Typed transitions carry fenced, quiescent, erased, staged, and installed facts into the next legal effect.
`InstalledReset.complete` consumes the staged lease, examines the payload again, and removes the exact owned fence.
The reset interface exposes no broker startup or readiness capability.
`RESET_INSTALLED` carries installation identity, version, and command without a service generation or readiness claim.
Changed payloads, changed fences, and repeated completion reject before success.

Lifecycle adapter tests establish these decisions and effect orders under controlled process outcomes.
Actual launchd retirement and IDEA restart behavior need their separate native procedures.

## Focused tests

- [ForceInstallationResetTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceInstallationResetTest.kt)
- [ForceReinstallationTest.kt](../../distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/ForceReinstallationTest.kt)

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
