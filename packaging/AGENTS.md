<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: be1cc28b93bb -->

# packaging

## Purpose

Implements installer support, installed-product acceptance, configuration ingress generation, and real-environment lifecycle checks.

## Key Files

- [hosted_enum_read_regression.py](hosted_enum_read_regression.py) - installed enum exclusion and member identity regression.

- [hosted_change_acceptance.py](hosted_change_acceptance.py) - bounded complete artifact inventory admission and native acceptance evidence.
- [configuration-schema.json](configuration-schema.json) - packaging-side configuration schema.
- [installation-lifecycle.py](installation-lifecycle.py) - installed lifecycle orchestration.
- [installed_acceptance_product.py](installed_acceptance_product.py) - acceptance product model.
- [run-installed-product.py](run-installed-product.py) - installed-product runner.
- [install-local.sh](install-local.sh) and [install-checkout.sh](install-checkout.sh) - packaging shell boundaries.
- [test-installer-entrypoint.py](test-installer-entrypoint.py) - public remote-installer command contract.
- [test-installed-product.sh](test-installed-product.sh) - installed product and local knowledge acceptance entry.
- [run-hosted-change-acceptance.py](run-hosted-change-acceptance.py) - staged broker, CLI, and plugin change workflow in a private native IDE.
- [hosted_read_regression.py](hosted_read_regression.py) - native CLI/provider read regression and bounded continuation checks.
- [hosted_read_requests.py](hosted_read_requests.py) - typed traversal fixture requests with explicit strategy and retained resume position.
- [hosted_budget_read_regression.py](hosted_budget_read_regression.py) - independent caller-grant forwarding and retained traversal replay across installed read surfaces.
- [native_fixture_probe.py](native_fixture_probe.py) - typed native fixture control responses and readiness evidence admission.
- [native_provider_qualification.py](native_provider_qualification.py) - closed, payload-free provider startup evidence retained by native read reports.

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
- For provider startup rejection, inspect `providerQualification` in the native report; `hosted_read_transport.py` admits the bounded startup handshake before tool calls.
