---
type: System Explanation
title: Release candidates and promotion
description: Explains how CI qualifies source-bound build candidates, admits their artifacts for reuse, and publishes validated releases. Covers CI policy, component inventory constraints, and publication ordering.
tags:
- kast
- distribution
- release
code_sources:
- path: .github/workflows/ci.yml
- path: .github/scripts/release/build_candidate.py
- path: .github/scripts/release/ci-candidate.py
- path: .github/scripts/release/publish-release.sh
- path: distribution/release/component_release.py
- path: distribution/release/component-release.schema.json
- path: packaging/composed_release.py
- path: .github/scripts/release/developer_catalog.py
- path: .github/scripts/release/publish_developer.py
verified:
  - by: openwiki/0.9.0
    at: 2026-11-08T22:20:55.770Z
sources:
  - id: openwiki-source-43d206677342592403ab5f63
    resource: repo://.github/scripts/ci/validation_outcome.py
  - id: openwiki-source-8c864abdf1b09fa67b48adbc
    resource: repo://.github/scripts/release/build_candidate.py
  - id: openwiki-source-939501634c56c6ffea2fec6b
    resource: repo://.github/scripts/release/developer_catalog.py
  - id: openwiki-source-f02c30e87f3db4d071a7be5b
    resource: repo://.github/scripts/release/publish_developer.py
  - id: openwiki-source-164e2da859b5277df81c7d94
    resource: repo://.github/workflows/ci.yml
  - id: openwiki-source-b0e51d5f05a72ea5b0fff73c
    resource: repo://distribution/release/component_release.py
  - id: openwiki-source-8ac360e10edcbd99c75fa338
    resource: repo://distribution/release/component-release.schema.json
  - id: openwiki-source-a4a155ae2c50f22d2a80ceaa
    resource: repo://packaging/managed_release.py
generated: { by: "openwiki/0.9.0", at: "2026-11-08T22:20:55.770Z" }
---

# Release candidates and promotion

## CI policy and candidate lifecycle

CI's `scope` job checks out the event's exact head and derives a validation plan. Documentation-only validation is limited to reviewed authored site inputs; product, installer, generated-schema, script, build-configuration, protected, mixed, and unknown changes keep the applicable broader obligations. The aggregate gate verifies the selected plan and check outcomes rather than treating an arbitrary passing run as authorization.

When product work is required, `source-work` attempts candidate reuse only on a main push, with semantic fixtures disabled. If no reusable candidate is admitted, it builds and validates the payload, then retains the candidate artifact with a manifest identifying repository, source commit and tree, CI run and attempt, event, platform, toolchain, version, and assets (names, byte counts, SHA-256). Candidate identity is therefore tied to tested inputs and producer, not just a version label.

```mermaid
flowchart TD
    Scope["CI selects validation plan"] --> Docs{"Documentation-only?"}
    Docs -->|yes| DocsGate["Validate docs and gate outcome"]
    Docs -->|no| Product["Run product obligations"]
    Product --> Reuse{"Eligible main push reuse?"}
    Reuse -->|candidate admitted| Retain["Reuse admitted payload"]
    Reuse -->|missing or ineligible| Build["Build and test payload"]
    Build --> Seal["Seal manifest and artifact"]
    Retain --> Gate["Aggregate verifies selected checks"]
    Seal --> Gate
    DocsGate --> Publish{"Publication outcome"}
    Gate --> Publish
    Publish -->|DOCS_VALIDATED| NoRelease["No product candidate or pointer update"]
    Publish -->|PRODUCT_VALIDATED| Promote["Admit and promote product candidate"]
```

This diagram shows how CI scope, candidate creation or reuse, and validation outcomes constrain publication.

## Documentation and developer publication

Developer promotion distinguishes `DOCS_VALIDATED` from `PRODUCT_VALIDATED`. The documentation outcome completes without creating a candidate, promotion record, product release, or updating the developer pointer; it does not associate a documentation revision with an older binary. Product publication requires the outcome record from the exact successful main push run and attempt. Missing, malformed, contradictory, or mismatched evidence rejects promotion.

On a main push, `build_candidate.py reuse` looks for the candidate associated with the exact successful producer workflow and source, checks source-tree equality, and admits downloaded files against the candidate inventory and digests. Equal trees permit reuse across a squash merge whose commit ID differs, while the promotion record preserves both the candidate build revision/producer and merged revision/main run. `MISSING` means no candidate was available under policy and permits a fresh build; rejection of a candidate is not permission to reinterpret changed bytes as the tested payload. Incomplete observations, wrong producers, source mismatch, payload mismatch, destination contamination, and download errors fail closed.

## Independent component releases

`distribution/release/component_release.py` admits Control and Host artifacts independently. Its schema-backed records bind the component/version and exact source revision to archive name, digest, size, and component-specific contract metadata. Admission requires exactly the expected owned files and checksum companions; checksums, archive size and digest, and archive contents are verified. Host variants carry their IntelliJ release line, and paired Host variants must agree on the hosted contract. Control and Host archives therefore cannot be substituted merely because their version strings match.

`packaging/composed_release.py` composes a fresh pair for an ordinary installation, while `assembleControlRelease` and `assembleHostRelease` assemble their respective independent components. Hosted-contract equality and platform admission constrain compatible mixed-version combinations. This component release boundary is separate from the CI candidate manifest and its source/run provenance.

## Published main catalog

The developer catalog retains at most five already-published immutable releases. Entries include main revision, original build revision, source tree, platform, tag/version, and candidate/promotion digests. Ordering follows proven main ancestry, not job completion time or numeric version; an out-of-order publication cannot displace a newer selection. Guarded publication admits assets before updating the catalog and latest pointer together, and rereads after contention.

## Focused verification

Candidate parsing, producer qualification, source-tree reuse, payload integrity, and failure paths are exercised by `.github/scripts/release/test_build_candidate.py` and `distribution/release/test_ci_candidate.py`. Component inventory, record ownership, checksums, and archive constraints are covered by the component release tests. CI validation policy and exact outcome handling have focused tests under `.github/scripts/ci/`.
