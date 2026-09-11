<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: d1fdc1c49fc3 -->

# packaging

## Purpose

Implements installer support, installed-product acceptance, configuration ingress generation, and real-environment lifecycle checks.

## Key Files

- [configuration-schema.json](configuration-schema.json) - packaging-side configuration schema.
- [installation-lifecycle.py](installation-lifecycle.py) - installed lifecycle orchestration.
- [installed_acceptance_product.py](installed_acceptance_product.py) - acceptance product model.
- [run-installed-product.py](run-installed-product.py) - installed-product runner.
- [install-local.sh](install-local.sh) and [install-checkout.sh](install-checkout.sh) - packaging shell boundaries.
- [test-installed-product.sh](test-installed-product.sh) - installed product acceptance entry.

## Subdirectories

- Python modules at this root - installation, environment, and acceptance orchestration.
- `test-*.py` and `test-*.sh` - focused executable evidence.

## Entry Points

- Public installation begins at root `install.sh` and delegates into this directory.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/distribution.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For a packaging failure, start with the failing test, then the corresponding lifecycle/environment module.
- Cross-check owned paths and configuration meaning against `distribution` contracts.
