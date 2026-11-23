---
type: "System Explanation"
title: "Installation and managed files"
description: "Explains Control and Host installation ownership, managed receipts, payload admission, and endpoint identity."
tags: [kast, distribution]

sources:
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-a387d9d545afff426d34a10a
    resource: repo://build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-a3af3d4f4fd16f9ce429f3c5
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationManifest.kt
  - id: openwiki-source-c4c5adde2b0966463aa6381b
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/OneShotInvocationRecord.kt
  - id: openwiki-source-dddd3dbe5fd2074f6edb696f
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InternalManagementInstall.kt
  - id: openwiki-source-689991f3a476668d88b8c28b
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PublicQueryDispatch.kt
  - id: openwiki-source-d1f8b0a2ac011ef2d36005a5
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
  - id: openwiki-source-f3718a29d9c6d868d0d9d73e
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
  - id: openwiki-source-a27b421c9c1d82b40bd5da73
    resource: repo://packaging/internal/bootstrap/bootstrap.sh.in
  - id: openwiki-source-9685c35bc7adaf6e5678720b
    resource: repo://packaging/internal/bootstrap/generate_bootstrap.py
  - id: openwiki-source-f819c9a16e09783f5c943c2d
    resource: repo://packaging/internal/installation/immutable_store.py
  - id: openwiki-source-e2f98663750a2e6ad150484d
    resource: repo://packaging/internal/installation/installation-lifecycle.py
  - id: openwiki-source-9f2a730e7185b57c14ac08e8
    resource: repo://packaging/internal/installation/managed_installation.py
  - id: openwiki-source-496462e31bbb3fc9156abfe9
    resource: repo://packaging/internal/installation/managed_release.py
  - id: openwiki-source-9aba602a70fbe25d03d80e37
    resource: repo://packaging/internal/installation/managed_update_contract.py
  - id: openwiki-source-68861fd33e9641882305aee3
    resource: repo://packaging/internal/product/local_product_install.py
  - id: openwiki-source-07f32471b5605b546192361f
    resource: repo://packaging/internal/product/managed_product_installation.py
  - id: openwiki-source-55a974a8699e80e32954af33
    resource: repo://packaging/internal/product/managed_product_removal.py
  - id: openwiki-source-558cf2932a7142fbf6311f57
    resource: repo://packaging/internal/product/product_removal_recovery.py
  - id: openwiki-source-678716bd23af50e8bbe1978b
    resource: repo://packaging/internal/product/product_state_removal.py
  - id: openwiki-source-9947fbfd711e87d328509a2c
    resource: repo://packaging/internal/verification/harness_launchd.py
  - id: openwiki-source-12d7829bb8c1e9c898163e3a
    resource: repo://packaging/tests/test-install-checkout.py
  - id: openwiki-source-d660a64cf3c39a2f406a497b
    resource: repo://packaging/tests/test-installation-lifecycle.py
  - id: openwiki-source-d4b63d273ed6048ab72eda6d
    resource: repo://packaging/tests/test-managed-bootstrap.py
  - id: openwiki-source-abab1b24f64b3271633d720d
    resource: repo://packaging/tests/test-managed-build-record.py
  - id: openwiki-source-db7ba27a263287dfa200741e
    resource: repo://packaging/tests/test-managed-installation.py
  - id: openwiki-source-40badbf2c7286dd74fa8aee8
    resource: repo://packaging/tests/test-managed-state-admission.py
generated: { by: "codex", at: "2026-11-22T21:35:10.249Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-23T00:44:58.019Z
---



# Installation and managed files

## Select the product before effects

The public installer selects one complete product from stable, preview, or explicit local artifact evidence.
Published schema 2 and 3 product records bind both supported targets to exact component and native qualification identities.
Local schema 4 records instead bind one supported target with `LOCAL` producer evidence.
They invent neither CI runs nor evidence for a second platform that was never built.
Published-record admission rejects local records; installed-record admission retains both forms.
Selection is independent of the checkout and installed version.
A retained legacy component pair cannot satisfy complete-product admission.
Installed Control launchers need no user JVM, IDEA installation, Python, or uv.
Checkout assembly remains a separate developer boundary, not a product prerequisite.

`install.sh --local-artifact DIRECTORY` reads `build-record.json` and the selected target's recorded files under `payload/`.
`LocalReleaseSource` creates a pinned product selection and supplies local bytes to the same `download_composition` and activation owners.
Missing, changed, or linked product files reject without product HTTP fallback.
A captured `--resolved-selection` that names a local record without `LocalReleaseSource` rejects with `LOCAL_ARTIFACT_REQUIRED` before any release read.
This selects the canonical global installation, not a separate contributor root.
Local records require `PinnedBuild`, no public version, and the matching developer tag and source revision.
The bootstrap still downloads pinned private Python and `defusedxml`; local product selection does not make setup offline.
The local selector cannot combine with channel, version, or commit selectors.

`python3 packaging/internal/product/local_product_install.py build --source CLEAN_CHECKOUT --output DIRECTORY` builds an export without installing globally.
The current builder supports macOS ARM64 and requires a clean committed source checkout.
It shares actual assembly and native component qualification with contributor installation, then calls `product_producer.seal_target` to bind the resulting artifacts and evidence.
The export contains `build-record.json`, recorded files under `payload/`, and qualification evidence and logs under `evidence/`.
Its receipt reports `LOCAL_PRODUCT_BUILT` and `releaseQualification: NOT_ESTABLISHED`.
Use the generated `install.sh --local-artifact DIRECTORY` separately for canonical installation.
These source contracts do not establish that a particular native build or global installation has succeeded.

`generate_bootstrap.py` owns the embedded engine, retained lifecycle support, and fixed data inventory.
Gradle stages its generated support projection under `share/kast`.
That projection carries no copy of the root `install.sh`; the generator rejects `install.sh` as a support member.
The former `install-legacy.sh`, `install-local.sh`, and `install-checkout.sh` entry points are removed.
Upgrade, removal, and recovery run the retained `install-candidate.sh`, a private lifecycle bridge rather than another public installer.
The bootstrap rejects the retired `--developer-latest` alias; `--channel preview` selects preview.
The separate contributor `install` command uses `local_product_install.py` and the existing product activation owner under an isolated root.
Contributor removal retires the semantic coordinator launchd jobs registered under that root, also when stop or uninstall fails, and reports `LAUNCHD_JOBS_REMAIN` when one survives.

Control owns the management executable, coordinator, adapters, private launchers, and installed configuration.
The managed headless Host runtime and its vendor base are immutable store entries that product activation publishes.
The standalone desktop Host installer is removed, and release admission rejects a release that still carries it.
Exact hosted contract identity and platform admission establish compatibility between Control and Host.
Version equality alone does not establish that compatibility.

## Control replacement

The Python installation coordinator, `managed_installation.py`, owns Control replacement.
It examines the selected product and payload inventory before replacement.
`--dry-run` reports the admitted plan without activation effects.
An applied replacement coordinates prior retirement, saved configuration, recovery snapshots, and payload installation.
The private management executable accepts only the installer's `managed-retire`, `preflight-reset`, and `reset` requests.

The managed root contains one ordinary `installation` directory.
The Control payload has one native executable, `share/kast/libexec/kast-management`, which must equal its Go producer's output.
Thin launchers select management, query, MCP, service, daemon, Codex, or installer entry points through that executable.
The `share/kast/jvm-control-v1` marker selects the JVM layout: the executable runs one JVM entry from JARs under `lib`.
Verification rejects class files, JARs outside `lib`, a second native executable, and symbolic links.
The verifier logs the bounded executable count before enforcing exactly one Control executable.
The Python `inspect_control` admission applies the same layout rules and checks the executable's target header.
Verification also rejects retired task payloads, `share/kast/install.sh`, and `share/kast/knowledge`, including its descendants.
The installed knowledge generator, extraction path, and retrieval implementation are retired; repository citation checks and module reports remain separate.
Published-release admission still accepts the earlier `native-control-v1` layout and the historical layout without either marker.
`ManagedInstallationOwnedTree` admits exact destructive-recovery trees under the physical selected installation.
Its admission rejects aliases and symlink substitutions before deletion.
Unknown paths do not become owned because their names resemble Kast artifacts.

## Product activation and retained composition recovery

`managed_installation.py` routes a newly admitted product to `managed_product_installation.activate`.
That owner publishes verified runtime bytes and their separate immutable vendor base.
It stages Control, saves exact management and command preimages, and publishes the existing replacement journal only after those records are durable.
Recovery admits the recorded selection and retires the prior installation before moving payloads.
`ProductInstalled` reports `NOT_STARTED`; payload activation does not prove a loaded Host or workspace readiness.
Activation writes no agent harness configuration.

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

Each state admission check that rejects raises a typed rejection with a closed failure code and its owning component.
It emits one structured observation that names at most eight sorted installation-relative entries, never their contents.
The installer outcome carries the same evidence in `Rejected.state`, and removal reports a state rejection with the same evidence.
One mapping serves activation, resume, and the entry point.
An `OSError` that carries an `errno` reports `FILESYSTEM_REJECTED`, and `OWNERSHIP_UNPROVEN` remains only for checks that still raise `ValueError`.
Any other exception reports `INSTALLATION_DEFECT` with its class name; an `AssertionError` is re-raised rather than reported as an outcome.

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

The bootstrap disables Python bytecode writes before importing its installed helpers.
Those imports preserve the exact installed payload inventory.

Only product journals are recoverable.
When `recovery/replacement/receipt.json` records a product replacement, the coordinator resumes it through `managed_product_installation.resume`.
Any other recorded journal, including a legacy composition record, fails closed with `RECOVERY_REQUIRED` and `RECOVERY_UNPROVEN`.
The product resume path itself rejects a record that carries a composition.
A recovery-only invocation does not select a new release.

## Recovery and evidence limits

Control recovery keeps the previous payload and qualified settings when replacement fails.
Failures during retirement, retention, activation, or recovery remain explicit.

Without a workspace registry, retirement admits only a stopped coordinator with exact empty stores and private ownership.
Current thread and invocation stores need their exact layout versions, empty record directories, and empty lock files.
The protocol cache must stay empty, and desktop discovery can contain only its empty lock file.
Durable records, unknown artifacts, live readiness evidence, symlinks, and foreign ownership still block retirement.

Installer tests establish payload, state, and artifact protection under private files.
They do not establish that a running Host loaded the new runtime.
Read native lifecycle evidence before a claim about loaded components or workspace semantics.

## Focused tests

- [test-installer-entrypoint.py](../../packaging/tests/test-installer-entrypoint.py)
- [test-managed-installation.py](../../packaging/tests/test-managed-installation.py)
- [test-managed-build-record.py](../../packaging/tests/test-managed-build-record.py)
- [test-managed-state-admission.py](../../packaging/tests/test-managed-state-admission.py)

## Source owners

- [install.sh](../../install.sh)
- [generate_bootstrap.py](../../packaging/internal/bootstrap/generate_bootstrap.py)
- [local_product_install.py](../../packaging/internal/product/local_product_install.py)
- [managed_update_contract.py](../../packaging/internal/installation/managed_update_contract.py)
- [managed_product_installation.py](../../packaging/internal/product/managed_product_installation.py)
- [managed_installation.py](../../packaging/internal/installation/managed_installation.py)
- [managed_release.py](../../packaging/internal/installation/managed_release.py)
- [InternalManagementInstall.kt](../../distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/InternalManagementInstall.kt)
- [VerifyDistributionTasks.kt](../../build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt)
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
