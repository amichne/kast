# Kast documentation

This repository contains the authored Kast pages, Zensical configuration, captured CLI contract, generated operation graph, and local documentation validation tooling.

## Use

```sh
python3 -m pip install -r requirements-docs.txt
python3 scripts/docs.py generate
python3 scripts/docs.py check --skip-source-existence
zensical build --strict
```

Validate page metadata and its source evidence from the repository root:

```sh
python3 scripts/docs.py check --repo .
```

## Refresh from a release candidate

```sh
python3 scripts/docs.py refresh --kast /absolute/path/to/kast
python3 scripts/docs.py check --repo .
zensical build --strict
```

`refresh` captures the exact installed schema and help. It then regenerates the operation graph and reference pages.

## Key files

- `INFORMATION_ARCHITECTURE.md`: page model, join, invariants, and refresh flow.
- `docs/public/operations/`: capability-grouped primary pages for every canonical operation.
- `docs/_data/kast-schema.json`: captured installed machine contract.
- `docs/_data/kast-docs.json`: generated page-to-operation graph.
- `scripts/docs.py`: generator, refresh, validation, and impact lookup.
- `docs_macros.py`: Zensical macro that injects exact CLI links into pages.
