---
type: Module Guide
title: "Kast packaging"
description: "Owns installer adapters, isolated runtime archives, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts."
resource: "repo://packaging"
tags: [kast, navigation]
timestamp: "2026-11-10T05:30:08Z"
source_revision: 85d30d515a1d777c5a83781f40813e32e69fa2e6
code_sources:
  - path: packaging/tests/test_composition_platform.py
  - path: packaging/composed_bundle.py
  - path: packaging/tests/test_composition_bundle.py
  - path: packaging/internal/composition_platform.py
  - path: packaging/internal/composed_host.py
  - path: packaging/internal/composed_host_probe.py
  - path: packaging/internal/composition_recipe.py
  - path: packaging/managed_headless.py
  - path: packaging/pyproject.toml
  - path: packaging/internal/generate-public-query.py
  - path: packaging/internal/host-installation.py
  - path: packaging/internal/generate_bootstrap.py
  - path: packaging/internal/local_product_install.py
  - path: packaging/internal/mcp_dependency_artifact.py
  - path: packaging/internal/mcp_environment.py
  - path: packaging/internal/installation-lifecycle.py
  - path: packaging/internal/installation-recovery.py
  - path: packaging/internal/run-installed-product.py
  - path: packaging/internal/run-portable-tests.py
  - path: packaging/tests/test-host-installation.py
  - path: packaging/internal/reload_hosted.py
  - path: packaging/tests/test_reload_hosted.py
  - path: packaging/internal/install-candidate.sh
  - path: packaging/internal/managed_installation.py
  - path: packaging/internal/managed_release.py
  - path: packaging/internal/managed_update_contract.py
  - path: packaging/internal/managed_update_journal.py
  - path: packaging/internal/host_directory_exchange.py
  - path: packaging/tests/test-installer-entrypoint.py
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-11-07 | hash: ce2893d75164 -->

# packaging

## Purpose

Owns installer adapters, isolated runtime archives, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts.

## Key Files

- [generate_bootstrap.py](internal/generate_bootstrap.py) - canonical bootstrap and retained support membership.
- [local_product_install.py](internal/local_product_install.py) - contributor assembly, qualification, and canonical product activation.
- [host-installation.py](internal/host-installation.py) - Host plugin installation and receipts.
- [managed_headless.py](managed_headless.py) - archive assembly without product installation.
- [composed_host.py](internal/composed_host.py) - module-based assembly from a pinned donor inventory.
- [composed_bundle.py](composed_bundle.py) - authenticated product input archives.
- [mcp_dependency_artifact.py](internal/mcp_dependency_artifact.py) - producer-only target-specific dependency materialization.
- [mcp_environment.py](internal/mcp_environment.py) - admitted dependency copying and isolated MCP preparation without a package manager.
- [composition_platform.py](internal/composition_platform.py) - pinned macOS ARM64 and Linux x86-64 donors.
- [composition_recipe.py](internal/composition_recipe.py) - pinned roots and plugin provider candidates.
- [composed_host_probe.py](internal/composed_host_probe.py) - native callers, Java references, and result rejection.
- [installation-lifecycle.py](internal/installation-lifecycle.py) - selected-installation retirement and removal.
- [installation-recovery.py](internal/installation-recovery.py) - Control recovery and update sealing.
- [generate-public-query.py](internal/generate-public-query.py) - Kotlin and harness projections from the authored public schema.
- [run-installed-product.py](internal/run-installed-product.py) - complete-product native lifecycle acceptance with explicit qualified inputs.
- [run-portable-tests.py](internal/run-portable-tests.py) - the offline packaging test inventory.

## Subdirectories

- [internal/](internal/) - private implementation and contributor tooling.
- [tests/](tests/) - packaging tests and their fixture adapter.
- [topology/](topology/) - explicit Git byte attribution for supplied historical paths.

## Entry Points

- Public installation starts at the root `install.sh`.
- `install-candidate.sh` invokes the candidate coordinator. It does not replace the current public bootstrap.
- Candidate admission reads `managed-install-v1`, component manifests, and immutable selected assets before active installation effects.
- For offline packaging tests, run `python3 packaging/internal/run-portable-tests.py` from the repository root.
- For managed and composition cases, run `mise exec -- uv run --locked --project packaging pytest packaging -q`.
- The portable runner selects `test-*.py`. The project command also selects `test_composition_*.py`.
- Use the [isolated runtime instructions](../docs/public/reference/installation.mdx#isolated-runtime) for archive installation.
- Read the [composition review](../docs/reviews/composed-analysis-host.md) for donor inputs, assembly commands, and native evidence limits.
- The Gradle task `installedProductTest` needs complete candidate and different qualified baseline products, pinned interpreter inputs, and new evidence paths.
- Native reset scripts need the disposable installation setup stated in their own arguments and documentation.
- [test-host-installation.py](tests/test-host-installation.py) - Host target and receipt protection.
- [test-installer-entrypoint.py](tests/test-installer-entrypoint.py) - canonical bootstrap ingress and complete-product acceptance input rejection.
- For hook envelopes and workspace preparation commands, run `mise exec -- uv run --locked --project packaging pytest packaging/tests/test-agent-onboarding.py`.
- These cases examine configured commands and host envelopes. They do not establish live IntelliJ preparation.

## Navigation Hints

- `prepare_runtime` stages the Host bootstrap and admitted component bytes without activating Control.
- `composed_bundle.py` packs the canonical generated `install.sh` with runtime inputs and matching Kast components.
- The installer leaves the Host stopped and the private profile without a workspace grant.
- The first explicit semantic request uses native workspace admission for exact-root trust.
- The standalone Python installer, `kast-task` launcher, desktop shell installers, and Gradle `installLocal` task are retired.
- The native probe uses a disposable fixture and a fresh evidence directory.
- Add `composed-host-fixture-overlay` to the fixture copy. Keep the original callback fixture because the probe uses exact source paths.
- Assembly receipts report artifact identity. Native probe results cover their stated cases.
- `packaging/uv.lock` owns the project test and tool dependencies.
- `composed_bundle.py` and `managed_headless.py` stay at this level to preserve embedded bootstrap bytes and their repository-relative assembly paths.
- The bootstrap generator maps source paths to unchanged delivered names. Keep installed recovery modules beside their sibling imports.
- Source-level script callers need `packaging/internal` and `packaging` on `PYTHONPATH`. Direct test callers also need `packaging/tests`.

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

- For a helper change, start with its focused tests and selected-installation rejection cases.
- For generated syntax, change the authored schema and generator together, then examine generation parity.
- Keep semantic rules in their Kotlin owners. Packaging tests do not establish live IDEA or Codex behavior.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
