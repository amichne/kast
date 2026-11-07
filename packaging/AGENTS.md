---
type: Module Guide
title: "Kast packaging"
description: "Owns installer adapters, development containers, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts."
resource: "repo://packaging"
tags: [kast, navigation]
timestamp: "2026-11-07T19:51:31Z"
source_revision: 08bc4e75693a260c1bfe0ffaad667800934cc0ed
code_sources:
  - path: bin/kast-container
  - path: packaging/dev_container.py
  - path: packaging/container_compile.py
  - path: packaging/container_install.py
  - path: packaging/container/Dockerfile
  - path: packaging/container/compiler.Dockerfile
  - path: packaging/container/kastbox
  - path: packaging/test_dev_container.py
  - path: packaging/test_composition_platform.py
  - path: bin/setup-kast
  - path: bin/kast-task-vm
  - path: packaging/task_vm.py
  - path: packaging/task_vm_guest.py
  - path: packaging/test_composition_task_vm.py
  - path: packaging/composed_bundle.py
  - path: packaging/composition_platform.py
  - path: packaging/composed_host.py
  - path: packaging/composed_host_probe.py
  - path: packaging/composition_recipe.py
  - path: packaging/managed_headless.py
  - path: packaging/pyproject.toml
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
<!-- generated: 2026-11-07 | hash: ce2893d75164 -->

# packaging

## Purpose

Owns installer adapters, development containers, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts.

## Key Files

- [install-checkout.sh](install-checkout.sh) - checkout installation with selected IDEA JBR admission.
- [install-local.sh](install-local.sh) - staged-product installation.
- [host-installation.py](host-installation.py) - Host plugin installation and receipts.
- [managed_headless.py](managed_headless.py) - archive assembly and the shared private installer.
- [composed_host.py](composed_host.py) - module-based assembly from a pinned donor inventory.
- [composed_bundle.py](composed_bundle.py) - authenticated portable setup bundles and installer dispatch.
- [task_vm.py](task_vm.py) - offline macOS ARM task coordination through Tart.
- [task_vm_guest.py](task_vm_guest.py) - the shared installer and literal task process inside the guest.
- [composition_platform.py](composition_platform.py) - pinned donor and layout selection for macOS ARM, Linux ARM64, and Linux x86_64.
- [dev_container.py](dev_container.py) - native image builds and persistent repository copies in a dedicated Colima VM.
- [container_compile.py](container_compile.py) - native Control compilation with digest-pinned GraalVM.
- [container_install.py](container_install.py) - native donor admission and composition through the shared installer.
- [composition_recipe.py](composition_recipe.py) - pinned roots and plugin provider candidates.
- [composed_host_probe.py](composed_host_probe.py) - native callers, Java references, and result rejection.
- [installation-lifecycle.py](installation-lifecycle.py) - selected-installation retirement and removal.
- [installation-recovery.py](installation-recovery.py) - Control recovery and update sealing.
- [generate-public-query.py](generate-public-query.py) - Kotlin and harness projections from the authored public schema.
- [run-installed-product.py](run-installed-product.py) - archive and installer smoke in an owned temporary root.
- [run-portable-tests.py](run-portable-tests.py) - the offline packaging test inventory.

## Subdirectories

- [container/](container/README.md) - development image definitions, the `kastbox` shell adapter, and usage instructions.
- [topology/](topology/) - explicit Git byte attribution for supplied historical paths.

## Entry Points

- Public installation starts at the root `install.sh`.
- `install-candidate.sh` invokes the candidate coordinator. It does not replace the current public bootstrap.
- Candidate admission reads `managed-install-v1`, component manifests, and immutable selected assets before active installation effects.
- For offline packaging tests, run `python3 packaging/run-portable-tests.py` from the repository root.
- For managed and composition cases, run `mise exec -- uv run --locked --project packaging pytest packaging -q`.
- The portable runner selects `test-*.py`. The project command also selects `test_composition_*.py` and `test_dev_container.py`.
- For container policy and shell adapter cases, run `mise exec -- uv run --locked --project packaging pytest packaging/test_dev_container.py packaging/test_composition_platform.py -q`. These cases do not prove native compiler behavior.
- `mise exec -- kast-container --help` describes native development containers. The [container guide](container/README.md) covers prerequisites and the separate image build.
- Read the [composition review](../docs/reviews/composed-analysis-host.md) for donor inputs, assembly commands, and native evidence limits.
- The Gradle task `installedProductTest` exercises the assembled product.
- Native reset scripts need the disposable installation setup stated in their own arguments and documentation.
- [test-host-installation.py](test-host-installation.py) - Host target and receipt protection.
- [test-installer-entrypoint.py](test-installer-entrypoint.py) - published-channel selection and installer ingress.
- For hook envelopes and workspace preparation commands, run `python3 packaging/test-agent-onboarding.py`.
- These cases examine configured commands and host envelopes. They do not establish live IntelliJ preparation.

## Navigation Hints

- `install_prepared_runtime` owns the installer, bootstrap, private state, and wrapper for both assembly strategies.
- The composed recipe supports pinned macOS ARM, Linux ARM64, and Linux x86_64 donors. Keep vendor bytes unchanged and reject an existing destination.
- `bin/setup-kast` installs a digest-pinned Linux or macOS ARM bundle through `install_prepared_runtime`.
- `mise exec -- kast-task-vm --help` describes disposable macOS ARM tasks. Read the [VM report](../docs/reviews/macos-task-vm.md) for the image contract and native gaps.
- Bundle creation uses `python3 packaging/composed_bundle.py pack`. Read the [Linux install report](../docs/reviews/linux-composed-install.md) for its inputs.
- Explicit workspace trust applies only to the new private profile and the named existing repository.
- The native probe consumes a disposable fixture with explicit trust and a fresh evidence directory.
- Add `composed-host-fixture-overlay` to the fixture copy. Keep the original callback fixture unchanged because its oracle admits exact source paths.
- Assembly receipts carry no readiness proof. Native probe results cover only their stated corpus.
- `bin/kast-container` delegates to `dev_container.py`. Keep its script lock beside the launcher. `packaging/uv.lock` owns the project test and tool dependencies.
- The container builder selects explicit assembly modules from this directory. It reuses `install_prepared_runtime` through the existing donor and composition owners.
- Docker images, Docker build caches, and copied workspaces stay in the dedicated Colima runtime. The launcher does not publish them or copy installed caches into source.
- Keep repository edits inside the retained container copy until an explicit export. Network access is enabled by default; the container guide describes the isolation limits.

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
