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
  - id: openwiki-source-8c864abdf1b09fa67b48adbc
    resource: repo://.github/scripts/release/build_candidate.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-b0e51d5f05a72ea5b0fff73c
    resource: repo://distribution/release/component_release.py
  - id: openwiki-source-8ac360e10edcbd99c75fa338
    resource: repo://distribution/release/component-release.schema.json
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-16T06:54:44.448Z
---

# Release candidates and promotion

## One tested immutable candidate

CI checks out the exact pull-request head for its selected product work.
It creates and tests a candidate once, then seals its repository, source revision, source tree, run identity, toolchain, and asset inventory.
Each asset record keeps its name, byte count, and digest.
The candidate artifact belongs to that exact successful producer run.

The required aggregate examines the jobs selected by the scope decision.
After those checks succeed, it seals the selected plan, exact source revision, source tree, and producer run identities.
A successful unrelated run or a passing earlier head does not authorize the current candidate.
Product failures, missing producer evidence, and incomplete check state remain distinct rejection causes.

## Successful documentation validation

Only the reviewed authored site inputs can select documentation-only validation.
Installer examples, generated schemas, scripts, build configuration, protected paths, and unknown or mixed inputs keep full validation.
The documentation lane runs Mintlify, the Markdown parser, glossary tests, and glossary projection checks.

Developer promotion requires the outcome record from the exact successful main push run and attempt.
`DOCS_VALIDATED` completes without a candidate, promotion record, product release, or developer pointer update.
It does not assign the documentation revision to an earlier binary.
`PRODUCT_VALIDATED` is required before the existing candidate admission.
Missing, malformed, contradictory, or mismatched outcome evidence rejects promotion.

## Reuse after merge

`build_candidate.py` admits the merged source against the sealed candidate.
Tree equality permits reuse when a squash merge changes the commit identifier but preserves the tested source tree.
The promotion record keeps the candidate producer and merged revision together.
Downloaded assets must match the admitted inventory and digests before reuse.

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
`assembleRelease` composes a fresh pair for an ordinary installation.
Hosted contract equality and platform admission govern mixed-version compatibility.
Matching component release numbers alone do not establish a usable pair.

## Proof limits

Candidate and component gates establish source and artifact provenance under their admitted records.
They do not establish that an installed IDEA instance loaded a Host or enrolled a specific workspace.
Keep publication permission, artifact integrity, and installed semantic qualification as separate facts.

## Focused tests

- [test_build_candidate.py](../../.github/scripts/release/test_build_candidate.py)

## Source owners

- [ci.yml](../../.github/workflows/ci.yml)
- [required_checks.py](../../.github/scripts/ci/required_checks.py)
- [verify-checks.py](../../.github/scripts/ci/verify-checks.py)
- [build_candidate.py](../../.github/scripts/release/build_candidate.py)
- [component_release.py](../../distribution/release/component_release.py)
- [component-release.schema.json](../../distribution/release/component-release.schema.json)
- [plugin-release.gradle.kts](../../distribution/release/plugin-release.gradle.kts)
- [release-component.yml](../../.github/workflows/release-component.yml)

## Read next

- [Installation](installation-and-managed-files.md)
- [Architecture checks](../development/build-and-architecture.md)
- [Native qualification](../development/testing-and-native-qualification.md)
