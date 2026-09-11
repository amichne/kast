<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 28f4fb8dfd0c -->

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
- [run-hosted-change-acceptance.py](run-hosted-change-acceptance.py) - staged broker, CLI, and plugin change workflow in a private native IDE.
- [hosted_read_regression.py](hosted_read_regression.py) - native CLI/provider read regression and bounded continuation checks.
- [native_fixture_probe.py](native_fixture_probe.py) - typed native fixture control responses and readiness evidence admission.

## Subdirectories

- Python modules at this root - installation, environment, and acceptance orchestration.
- `test-*.py` and `test-*.sh` - focused executable evidence.

## Entry Points

- Public installation begins at root `install.sh` and delegates into this directory.
- Native hosted acceptance begins at `run-hosted-change-acceptance.py`; use its `--help` for required staged artifacts and bounded run controls.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/distribution.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For a packaging failure, start with the failing test, then the corresponding lifecycle/environment module.
- Cross-check owned paths and configuration meaning against `distribution` contracts.
- For fixture control or readiness failures, pair `native_fixture_probe.py` with the [isolated probe source](../change/intellij/src/nativeFixture) and its [contract tests](../change/intellij/src/nativeFixtureTest).
