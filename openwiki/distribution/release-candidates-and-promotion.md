---
type: System Explanation
title: Release candidates and promotion
description: Explains immutable CI candidates, exact-source component records, checksum
  admission, and byte-preserving release promotion.
tags:
- kast
- distribution
code_sources:
- path: .github/workflows/ci.yml
- path: .github/scripts/ci/required_checks.py
- path: .github/scripts/ci/validation_scope.py
- path: .github/scripts/ci/validation_outcome.py
- path: .github/scripts/ci/test_validation_outcome.py
- path: .github/scripts/ci/verify-checks.py
- path: .github/scripts/release/build_candidate.py
- path: distribution/release/component_release.py
- path: distribution/release/component-release.schema.json
- path: distribution/release/plugin-release.gradle.kts
- path: .github/workflows/release-component.yml
- path: .github/scripts/release/test_build_candidate.py
sources:
  - id: openwiki-source-0475f74ce9e8867a7d899c7d
    resource: repo://.github/scripts/ci/test_validation_outcome.py
  - id: openwiki-source-43d206677342592403ab5f63
    resource: repo://.github/scripts/ci/validation_outcome.py
  - id: openwiki-source-3da7270665885f2d3fdf6d53
    resource: repo://.github/scripts/ci/validation_scope.py
  - id: openwiki-source-8c864abdf1b09fa67b48adbc
    resource: repo://.github/scripts/release/build_candidate.py
  - id: openwiki-source-939501634c56c6ffea2fec6b
    resource: repo://.github/scripts/release/developer_catalog.py
  - id: openwiki-source-5e92c81a46b459b558f8901e
    resource: repo://.github/scripts/release/product_candidate.py
  - id: openwiki-source-e61ce41a823368fa4e25eb84
    resource: repo://.github/scripts/release/product_producer.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-876d58467609bbf82c40cdc2
    resource: repo://.github/scripts/release/test_build_candidate.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-804f227d6f0416062f3436fe
    resource: repo://.github/workflows/developer-release.yml
  - id: openwiki-source-b0e51d5f05a72ea5b0fff73c
    resource: repo://distribution/release/component_release.py
  - id: openwiki-source-8ac360e10edcbd99c75fa338
    resource: repo://distribution/release/component-release.schema.json
  - id: openwiki-source-eb518d021af0c0c5a9bcc85c
    resource: repo://packaging/managed_product.py
  - id: openwiki-source-a4a155ae2c50f22d2a80ceaa
    resource: repo://packaging/managed_release.py
  - id: openwiki-source-e7484ccb9a72cd71aa938a2f
    resource: repo://packaging/product_acceptance.py
  - id: openwiki-source-72a2e0c4bf1e54eeb4c8db6c
    resource: repo://packaging/product_lifecycle_probe.py
  - id: openwiki-source-fbdf7de26cc8b3b5b59b53a6
    resource: repo://packaging/test_product_acceptance.py
generated: { by: "amp", at: "2026-11-13T00:20:29.143Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T01:44:31.229Z
---

# Release candidates and promotion

## Seal components before installed acceptance

The complete-product producer runs the component-native harness before it seals each target.
Each `COMPONENT_NATIVE` receipt binds the source, harness, Control, runtime, and bundle identities.
The join requires both macOS ARM64 and Linux x86-64 from the exact producer attempt.
It records source revision, source tree, toolchain inputs, and asset names, sizes, and digests without rebuilding those assets.

The sealed build record then enters installed-product acceptance through normal managed admission.
Both detached `INSTALLED_PRODUCT_NATIVE` receipts are required before publication admission.
Joining those receipts does not rewrite the sealed build record.
Neither schema decoding nor a successful seal alone establishes installed-product acceptance.

The acceptance baseline needs sealed component qualification, not prior publication or installed acceptance.
Its source revision may match the candidate, but each target needs a different actual bundle/runtime/Control composition.
CI admits the exact retained baseline record, producer attempt, and successful sealing job.
A later failed job in that baseline run does not erase its sealing evidence.
The probe upgrades the baseline, removes it, installs the fresh candidate, and checks passive discovery before simultaneous first queries.
Three shipped public `kast query` callers start together from absent workspace state.
Every response must succeed, and a later warm query must retain their compiler Host identity.
It also checks both roots concurrently and protects external Gradle and foreign-profile sentinels.

The required aggregate examines the jobs selected by the scope decision.
After those checks succeed, it seals the selected plan, exact source revision, source tree, and producer run identities.
A successful unrelated run or a passing earlier head does not authorize the current candidate.
Product failures, missing producer evidence, and incomplete check state remain distinct rejection causes.

## Successful documentation validation

Only the reviewed authored site inputs can select documentation-only validation.
Installer examples, generated schemas, scripts, build configuration, protected paths, and unknown or mixed inputs keep full validation.
The documentation lane runs Mintlify, the Markdown parser, glossary tests, and glossary projection checks.

Developer promotion needs the outcome record from the exact successful main push or manual CI run and attempt.
The record and producer must agree on the workflow event.
Manual CI selects full validation and needs an explicit developer publication dispatch.
`DOCS_VALIDATED` completes without a candidate, promotion record, product release, or developer pointer update.
It does not assign the documentation revision to an earlier binary.
`PRODUCT_VALIDATED` is required before the existing candidate admission.
Missing, malformed, contradictory, or mismatched outcome evidence rejects promotion.

## Retained component-candidate reuse

The retained component-only candidate path in `build_candidate.py` admits a merged source against its sealed candidate.
Tree equality permits reuse when a squash merge changes the commit identifier but preserves the tested source tree.
The promotion record keeps the candidate producer and merged revision together.
Downloaded assets must match the admitted inventory and digests before reuse.
That legacy candidate is not a complete two-target private-runtime product.
Current developer publication uses `promote-product`, the complete build record, and detached installed acceptance instead.

A missing candidate and a rejected candidate are different states.
The workflow can build a candidate when no reusable candidate exists under its policy.
It cannot call changed bytes the previously tested artifact.
Destination contamination, payload mismatch, wrong producer, or source-tree mismatch rejects admission.

## Independent component releases

Control and Host have separate component records and archive ownership.
`component_release.py` admits the selected component, semantic version, exact source revision, contract evidence, and asset inventory.
The directory must contain the expected owned names and checksum companions.
The archive byte count and SHA-256 must match the record and checksum files.
Archive admission then examines the component's expected contents.

`assembleControlRelease` and `assembleHostRelease` package independent components.
`assembleRelease` composes a fresh component pair; it does not itself seal a complete product.
Hosted contract equality and platform admission govern mixed-version compatibility.
Matching component release numbers alone do not establish a usable pair.

## Bounded published preview discovery

The developer catalog contains at most five already published immutable products.
Each product entry keeps its main revision, release tag, complete build record, and record digest.
The record preserves build revision, source tree, both targets, and original producer identity.
Publishing a product converts discovery to product-only entries without deleting old releases or pins.
Main ancestry orders the entries. Job completion time and numeric version do not determine order.
An out-of-order publication cannot replace a newer selection.

Publication admits immutable release assets before it changes the catalog and latest pointer together.
The commit uses a guarded fast-forward update and rereads the current catalog after contention.
Conflicting identity, unproven ancestry, changed assets, and exhausted contention return finite failures.
An older main revision remains a selector only while it appears in this bounded discovery window.

The management command captures one admitted selection before the installer downloads artifacts.
`managed_release.py` rechecks the selected build-record digest and complete two-target record.
`managed_product.py` admits the target's exact archive bytes, native receipt, composed inventory, and component compatibility before effects.
Legacy main component-pair selections reject. Stable selections require an exact complete-product mapping.
It does not rerun latest selection after capture.

## Proof limits

Candidate and component gates establish source and artifact provenance under their admitted records.
They do not establish that an installed IDEA instance loaded a Host or enrolled a specific workspace.
Keep publication permission, artifact integrity, and installed semantic qualification as separate facts.
Local component execution does not establish clean-source CI, Linux qualification, or either detached installed-product acceptance receipt.
This explanation records the source contract, not a completed two-target acceptance run or publication.

## Focused tests

- [test_build_candidate.py](../../.github/scripts/release/test_build_candidate.py)

## Source owners

- [ci.yml](../../.github/workflows/ci.yml)
- [required_checks.py](../../.github/scripts/ci/required_checks.py)
- [verify-checks.py](../../.github/scripts/ci/verify-checks.py)
- [build_candidate.py](../../.github/scripts/release/build_candidate.py)
- [product_producer.py](../../.github/scripts/release/product_producer.py)
- [product_candidate.py](../../.github/scripts/release/product_candidate.py)
- [product_lifecycle_probe.py](../../packaging/product_lifecycle_probe.py)
- [product_acceptance.py](../../packaging/product_acceptance.py)
- [component_release.py](../../distribution/release/component_release.py)
- [component-release.schema.json](../../distribution/release/component-release.schema.json)
- [`plugin-release.gradle.kts`](../../distribution/release/plugin-release.gradle.kts)
- [release-component.yml](../../.github/workflows/release-component.yml)

## Read next

- [Installation](installation-and-managed-files.md)
- [Architecture checks](../development/build-and-architecture.md)
- [Native qualification](../development/testing-and-native-qualification.md)
