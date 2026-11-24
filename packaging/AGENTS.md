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
  - path: packaging/internal/composition/composition_platform.py
  - path: packaging/internal/composition/composed_host.py
  - path: packaging/internal/verification/composed_host_probe.py
  - path: packaging/internal/composition/composition_recipe.py
  - path: packaging/managed_headless.py
  - path: packaging/pyproject.toml
  - path: packaging/internal/query/generate-public-query.py
  - path: packaging/internal/bootstrap/generate_bootstrap.py
  - path: packaging/internal/product/local_product_install.py
  - path: packaging/internal/installation/installation-lifecycle.py
  - path: packaging/internal/installation/installation-recovery.py
  - path: packaging/internal/verification/run-installed-product.py
  - path: packaging/internal/verification/run-portable-tests.py
  - path: packaging/internal/bootstrap/install-candidate.sh
  - path: packaging/internal/installation/managed_installation.py
  - path: packaging/internal/installation/managed_release.py
  - path: packaging/internal/installation/managed_update_contract.py
  - path: packaging/internal/installation/managed_update_journal.py
  - path: packaging/tests/test-installer-entrypoint.py
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-11-07 | hash: ce2893d75164 -->

# packaging

## Purpose

Owns installer adapters, isolated runtime archives, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts.

## Key Files

- [generate_bootstrap.py](internal/bootstrap/generate_bootstrap.py) - canonical bootstrap and retained support membership.
- [bootstrap_release.py](internal/bootstrap/bootstrap_release.py) - pinned digests of the published Go bootstrap. `install.sh` runs it only for a preview dry run.
- [local_product_install.py](internal/product/local_product_install.py) - contributor assembly, qualification, and canonical product activation.
- [managed_headless.py](managed_headless.py) - archive assembly without product installation.
- [composed_host.py](internal/composition/composed_host.py) - module-based assembly from a pinned donor inventory.
- [composed_bundle.py](composed_bundle.py) - authenticated product input archives.
- [composition_platform.py](internal/composition/composition_platform.py) - pinned macOS ARM64 and Linux x86-64 donors.
- [composition_recipe.py](internal/composition/composition_recipe.py) - pinned roots and plugin provider candidates.
- [composed_host_probe.py](internal/verification/composed_host_probe.py) - native callers, Java references, and result rejection.
- [installation-lifecycle.py](internal/installation/installation-lifecycle.py) - Control payload and uninstall journal admission.
- [installation-recovery.py](internal/installation/installation-recovery.py) - exact tree, move and fence proofs for the product replacement journal.
- [generate-public-query.py](internal/query/generate-public-query.py) - Kotlin and harness projections from the authored public schema.
- [run-installed-product.py](internal/verification/run-installed-product.py) - complete-product native lifecycle acceptance with explicit qualified inputs.
- [run-portable-tests.py](internal/verification/run-portable-tests.py) - the offline packaging test inventory.

## Subdirectories

- [internal/](internal/) - private bootstrap, composition, installation, product, query, runtime, verification, and configuration families.
- [tests/](tests/) - packaging tests and their fixture adapter.
- [topology/](topology/) - explicit Git byte attribution for supplied historical paths.

## Entry Points

- Public installation starts at the root `install.sh`.
- `install-candidate.sh` invokes the candidate coordinator. It does not replace the current public bootstrap.
- Candidate admission reads `managed-install-v1`, component manifests, and immutable selected assets before active installation effects.
- For offline packaging tests, run `mise run suite` from the repository root.
- For managed and composition cases, run `mise run packaging`.
- The portable runner selects `test-*.py`. The project command also selects `test_composition_*.py`.
- Use the [isolated runtime instructions](../docs/public/reference/installation.mdx#isolated-runtime) for archive installation.
- Read the [composition review](../docs/reviews/composed-analysis-host.md) for donor inputs, assembly commands, and native evidence limits.
- The Gradle task `installedProductTest` needs complete candidate and different qualified baseline products, pinned interpreter inputs, and new evidence paths.
- Native reset scripts need the disposable installation setup stated in their own arguments and documentation.
- [test-installer-entrypoint.py](tests/test-installer-entrypoint.py) - canonical bootstrap ingress and complete-product acceptance input rejection.
- For hook envelopes and workspace preparation commands, run `mise run packaging-python -m pytest packaging/tests/test-agent-onboarding.py`.
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
- Source-level callers use the explicit family search paths in `pyproject.toml` or the portable runner. Direct test callers also need `packaging/tests`.
- Source entrypoints add only their named family paths. Isolated product probes use the delivered flat coordinator modules without a source fallback.
- Keep source moves in the generator's explicit mapping. Unknown source names must fail rather than select a directory by convention.

- `prepare_managed` stages and validates complete bytes before publishing its Host record paths.
- A synchronous preparation failure removes only re-admitted artifacts from that call. Ambiguous changes keep recovery evidence.
- The installation coordinator stores the returned Host record in its existing replacement receipt and owns its lock.
- Host effects also hold the existing profile-owned `host-install.lock` through `managed_lock`.
- An exact journal hard link reserves that lock across interruption. Ordinary Host installation and removal reject a reserved lock.
- `finish_managed_lock` releases only re-admitted transaction metadata after terminal proof. Existing canonical locks remain.
- Keep prior Host bytes and the active receipt preimage until runtime completion or proven restoration.
- The reload helper supplies internal compatibility admission and directory exchange. Its standalone command cannot replace the managed transaction.
- Managed hot reload uses the same Host receipt through `commit_managed_reload` and `rollback_managed_reload`.
- `seal_managed` admits terminal file facts before cleanup. The outer coordinator separately proves the required Host state.
- `managed_update_journal.py` owns shared recovery records. Keep it beside the offline recovery helper in the retained bundle.
- Read the [candidate evidence guide](../docs/reviews/managed-installation-cli.md) before qualification or public rollout.

- For a helper change, start with its focused tests and selected-installation rejection cases.
- For generated syntax, change the authored schema and generator together, then examine generation parity.
- Keep semantic rules in their Kotlin owners. Packaging tests do not establish live IDEA or Codex behavior.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
