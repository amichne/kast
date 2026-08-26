---
type: Documentation Contract
title: Documentation contract
description: How operation pages bind to the installed CLI schema and source evidence.
resource: kast://docs/contract
tags:
  - kast
  - documentation
  - okf
  - zensical
timestamp: '2026-08-21T00:00:00Z'
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt
    symbols: [CanonicalOperationDefinitions]
  - path: zensical.toml
  - path: scripts/docs.py
---

# Documentation contract

Each non-reserved Markdown concept is an OKF page with a non-empty `type`. Standard fields describe the concept. Producer fields connect the concept to Kast.

## Producer fields

| Field | Meaning |
| --- | --- |
| `kast_operations` | Canonical operation IDs referenced by the page. |
| `kast_operation_role` | `primary` when the page owns one operation; `related` when it explains one or more operations. |
| `kast_lifecycle_commands` | Non-semantic runtime commands referenced by the page. |
| `proof_level` | The strongest checked proof represented by the page: `contract`, `enterprise`, or `release`. |
| `code_sources` | Source paths and symbols used by Code Knowledge Base impact checks. |

## Mechanical invariants

1. Every operation in `kast --schema` has exactly one primary page.
2. A primary page names exactly one operation.
3. Its resource is `kast://operation/<operation-id>`.
4. Unknown operation and lifecycle IDs fail validation.
5. Generated references and the operation graph must match page metadata and the captured schema.
6. Every operation-linked page renders the CLI contract macro.

## Commands

```sh
python3 scripts/docs.py refresh --kast /absolute/path/to/kast
python3 scripts/docs.py generate
python3 scripts/docs.py check --repo .
python3 scripts/docs.py impact --operation symbol.discover
zensical build --strict
```

`refresh` is the only process that changes the captured schema and help. `generate` is deterministic for a fixed capture and page set.
