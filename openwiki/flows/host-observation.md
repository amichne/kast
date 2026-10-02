---
type: Runtime Flow
title: Experimental host observation
description: A manually evaluated IDEA script admits one host/project, writes bounded advisory observations, and retires through its original owner.
resource: file://experiments/host-observation
tags: [intellij, observation, lifecycle]
code_sources:
  - path: experiments/host-observation/observer.kts
    symbols: [KastHostObservation, CallbackGate, Session]
  - path: experiments/host-observation/controller.py
    symbols: [prepare, verify_receipt, stop_session, inspect_journal]
  - path: experiments/host-observation/protocol.schema.json
  - path: experiments/host-observation/run_host_acceptance.py
verified:
  - by: openwiki/0.6.1
    at: 2026-10-02T02:00:02.467Z
sources:
  - id: openwiki-source-65f037e368d15c01e8968a00
    resource: repo://experiments/host-observation/observer.kts
  - id: openwiki-source-1a61c49e0f5b1eee9e6f1a38
    resource: repo://experiments/host-observation/README.md
generated: { by: "codex", at: "2026-10-02T02:00:02.467Z" }
---

# Experimental host observation

The manual experiment is separate from Kast runtime composition. Preflight validates the exact installed build/engine and detaches project descriptors without registrations. Attach revalidates host incarnation and explicit project identity, reserves one shared admission directory, and installs VFS, indexing-history, and dumb-mode listeners under an original session owner.

Callbacks project bounded values through a revocable, counted gate. A bounded queue feeds one rotating journal writer. Coverage always includes an initial gap. Neither progress nor host sequences participate in [workspace publication](workspace-publication.md).

An exact-session stop request, project disposal, or failure closes admission. An independent finalizer disconnects registrations and waits for admitted callbacks and owned tasks. A stalled owner reports retirement as unconfirmed and keeps its shared admission. Only established retirement permits a terminal status and retention proof; a failed contender cannot clean up another session.

The [experiment runbook](../../experiments/host-observation/README.md) describes exact bounds, manual activation, validation commands, and engine-unload restrictions. No consumer, refresh scheduler, or index-copy capability is enabled.
