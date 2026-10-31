---
type: Module Guide
title: "Kast packaging"
description: "Owns installer adapters, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts."
resource: "repo://packaging"
tags: [kast, navigation]
timestamp: "2026-10-30T22:56:37Z"
source_revision: c00f3bfa71dd40e9897d714e378b21eacf673073
code_sources:
  - path: packaging/generate-public-query.py
  - path: packaging/host-installation.py
  - path: packaging/install-checkout.sh
  - path: packaging/install-local.sh
  - path: packaging/installation-lifecycle.py
  - path: packaging/installation-recovery.py
  - path: packaging/run-installed-product.py
  - path: packaging/run-portable-tests.py
  - path: packaging/test-host-installation.py
  - path: packaging/reload_hosted.py
  - path: packaging/test_reload_hosted.py
  - path: packaging/install-candidate.sh
  - path: packaging/managed_installation.py
  - path: packaging/managed_release.py
  - path: packaging/managed_update_contract.py
  - path: packaging/managed_update_journal.py
  - path: packaging/host_directory_exchange.py
  - path: packaging/test-installer-entrypoint.py
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-15 | hash: 76d6f8654970 -->

# packaging

## Purpose

Owns installer adapters, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts.

## Key Files

- [install-checkout.sh](install-checkout.sh) - checkout installation with selected IDEA JBR admission.
- [install-local.sh](install-local.sh) - staged-product installation.
- [host-installation.py](host-installation.py) - Host plugin installation and receipts.
- [installation-lifecycle.py](installation-lifecycle.py) - selected-installation retirement and removal.
- [installation-recovery.py](installation-recovery.py) - Control recovery and update sealing.
- [generate-public-query.py](generate-public-query.py) - Kotlin and harness projections from the authored public schema.
- [run-installed-product.py](run-installed-product.py) - archive and installer smoke in an owned temporary root.
- [run-portable-tests.py](run-portable-tests.py) - the offline packaging test inventory.

## Subdirectories

- [topology/](topology/) - explicit Git byte attribution for supplied historical paths.

## Entry Points

- Public installation starts at the root `install.sh`.
- `install-candidate.sh` invokes the candidate coordinator. It does not replace the current public bootstrap.
- Candidate admission reads `managed-install-v1`, component manifests, and immutable selected assets before active installation effects.
- For offline packaging tests, run `python3 packaging/run-portable-tests.py` from the repository root.
- The Gradle task `installedProductTest` exercises the assembled product.
- Native reset scripts need the disposable installation setup stated in their own arguments and documentation.
- [test-host-installation.py](test-host-installation.py) - Host target and receipt protection.
- [test-installer-entrypoint.py](test-installer-entrypoint.py) - published-channel selection and installer ingress.
- For passive context hooks, run `python3 packaging/test-agent-onboarding.py`. MCP session tests own IntelliJ preparation evidence.

## Navigation Hints

- Managed Host replacement uses `prepare_managed`, `commit_managed`, `rollback_managed`, and `seal_managed` from `host-installation.py`.
- `prepare_managed` stages and validates complete bytes before publishing its Host record paths.
- A synchronous preparation failure removes only re-admitted artifacts from that call. Ambiguous changes keep recovery evidence.
- The installation coordinator stores the returned Host record in its existing replacement receipt and owns its lock.
- Host effects also hold the existing profile-owned `host-install.lock` through `managed_lock`.
- An exact journal hard link reserves that lock across interruption. Ordinary Host installation and removal reject a reserved lock.
- `finish_managed_lock` releases only re-admitted transaction metadata after terminal proof. Existing canonical locks remain.
- Keep prior Host bytes and the active receipt preimage until runtime completion or proven restoration.
- The reload helper supplies internal compatibility admission and directory exchange. Its standalone command cannot replace the managed transaction.
- Managed hot reload uses the same Host receipt through `commit_managed_reload` and `rollback_managed_reload`.
- `host_directory_exchange.py` owns the macOS atomic directory swap. File exchange does not prove runtime activation.
- `seal_managed` admits terminal file facts before cleanup. The outer coordinator separately proves the required Host state.
- `managed_update_journal.py` owns shared recovery records. Keep it beside the offline recovery helper in the retained bundle.
- Read the [candidate evidence guide](../docs/reviews/managed-installation-cli.md) before qualification or public rollout.

- For a helper change, start with its corresponding `test-*.py` file and selected-installation rejection cases.
- For generated syntax, change the authored schema and generator together, then examine generation parity.
- Keep semantic rules in their Kotlin owners. Packaging tests do not establish live IDEA or Codex behavior.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
