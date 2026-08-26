# Kast documentation information architecture

## Decision

The atomic documentation unit is the canonical operation. Command families are navigation groups. Concepts may refer to many operations but cannot replace an operation's primary page.

## Layers

```text
Start
  → Agent setup
  → Capability groups
      → Primary operation pages
  → Concepts
  → Glossary
  → Generated reference
```

- **Start** owns the required developer journey: install, configure the agent, use Kast.
- **Primary operation pages** are grouped by capability and own user intent, outcome meaning, and the next compatible identity transition.
- **Concept pages** explain cross-operation invariants such as exact identity, outcomes, and runtime.
- **Glossary** is the sole terminology authority.
- **Generated reference** owns exact commands, operation IDs, lifecycle commands, version, and runtime identity.

## Page-to-operation join

Each authored page uses OKF front matter plus three producer extensions:

```yaml
---
type: CLI Operation
title: Resolve exact symbol identity
description: Refine one candidate selector into one exact selector.
resource: kast://operation/symbol.resolve
kast_operations:
  - symbol.resolve
kast_operation_role: primary
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolResolveRequest, SymbolResolveResult]
---
```

`scripts/docs.py generate` joins this metadata with `docs/_data/kast-schema.json` and writes:

- `docs/_data/kast-docs.json`: operation → primary page, related pages, command, anchor, and proof level.
- `docs/public/reference/operations.md`: generated human reference.
- `docs/public/reference/cli.md`: generated installed CLI reference.
- `docs/public/reference/kast-schema.json`: public machine contract.

`docs_macros.py` reads the generated graph and injects the exact command and reference links into every operation-linked page.

## Invariants

| Invariant | Enforcement |
| --- | --- |
| Every installed operation has one primary page | `scripts/docs.py check` |
| A primary page names one operation | `scripts/docs.py check` |
| Unknown operation IDs cannot render | generator and macro fail closed |
| Exact CLI syntax is not hand-maintained | captured schema and generated reference |
| Source changes identify affected pages | `code_sources` and `impact` |
| Navigation contains every primary page | `scripts/docs.py check` |
| Broken Markdown links fail delivery | `zensical build --strict` |

## Refresh flow

```text
installed kast
  ├─ --version
  ├─ --schema
  └─ --help for root, families, operations, lifecycle
        ↓
docs/_data capture
        ↓
page front matter + capture
        ↓
kast-docs.json + generated reference
        ↓
local checks + Zensical strict build
```

## Impact examples

```sh
python3 scripts/docs.py impact --operation relation.read
python3 scripts/docs.py impact   --changed-file protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
```

The operation query follows semantic ownership. The source query follows Code Knowledge Base evidence ownership.
