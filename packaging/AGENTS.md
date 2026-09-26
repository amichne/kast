<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-15 | hash: ea6cc05510b9 -->

# packaging

## Purpose

Implements installer support, installed-product acceptance, configuration ingress generation, and real-environment lifecycle checks.

## Key Files

- [hosted_configuration_continuity.py](hosted_configuration_continuity.py) - private saved-selector and inherited-configuration continuity through the staged CLI.

- [hosted_workspace_refresh_regression.py](hosted_workspace_refresh_regression.py) - disposable native file-refresh and Gradle-module reload acceptance, including failed import restoration.

- [hosted_repair_time_observation.py](hosted_repair_time_observation.py) - same-request native host stage, grant, reserve and transport timing evidence.

- [hosted_diagnostic_pages_regression.py](hosted_diagnostic_pages_regression.py) - bounded same-basis diagnostic drain and repeated warning occurrences.
- [hosted_source_failure_regression.py](hosted_source_failure_regression.py) - finite source failure evidence across CLI ingress and provider responses.

- [hosted_compact_source_regression.py](hosted_compact_source_regression.py) - lossless compact source parity and actual provider presentation admission.
- [hosted_vfs_overflow_regression.py](hosted_vfs_overflow_regression.py) - owned overflow burst, post-burst host receipt, stale refusal and guarded restoration.

- [hosted_read_policy.py](hosted_read_policy.py) - closed private-process presets for native default, enlarged-deadline, overflow and diagnostic paging qualification.
- [hosted_repair_budget_regression.py](hosted_repair_budget_regression.py) - bounded actual 10/20-second grant receipts across installed surfaces.
- [hosted_kotlin_call_regression.py](hosted_kotlin_call_regression.py) - compiled Kotlin call ownership and coverage oracle.

- [hosted_read_name_regression.py](hosted_read_name_regression.py) - preferred and legacy provider inputs retain canonical operations, schema and ordered facts.
- [installed_codex_lifecycle.py](installed_codex_lifecycle.py) - shared real-Codex handshake, private coordinator ownership, detach, and cleanup checks.
- [test-installed-codex-lifecycle.py](test-installed-codex-lifecycle.py) - private and legacy service-control selection for native acceptance.
- [released_coordinator_acceptance.py](released_coordinator_acceptance.py) - original installed wrapper and explicit Codex admission for coordinator qualification.

- [released_session_acceptance.py](released_session_acceptance.py) - owned child-shell wrapper, version and saved-configuration qualification.
- [released_upgrade_acceptance.py](released_upgrade_acceptance.py) - adjacent original-release upgrade and retained installation evidence.

- [hosted_wire_schema.py](hosted_wire_schema.py) - exact staged-jar endpoint schema admission for native peer frames.
- [hosted_raw_symbol_regression.py](hosted_raw_symbol_regression.py) - raw discovery candidates and compiler refinement through both read surfaces.
- [released_acceptance_product.py](released_acceptance_product.py) - original release assets through the tagged public installer in an owned fixture.
- [released_payload_identity.py](released_payload_identity.py) - installed payload comparison against original archive bytes.
- [released_tool_inventory.py](released_tool_inventory.py) - complete installed catalog and canonical bindings.
- [hosted_transport_observation.py](hosted_transport_observation.py) - bounded native log observations and named transport drain conditions.
- [hosted_enum_read_regression.py](hosted_enum_read_regression.py) - installed enum exclusion and member identity regression.

- [hosted_change_acceptance.py](hosted_change_acceptance.py) - bounded complete artifact inventory admission and native acceptance evidence.
- [configuration-schema.json](configuration-schema.json) - packaging-side configuration schema.
- [installation-lifecycle.py](installation-lifecycle.py) - installed lifecycle orchestration and private or legacy service retirement.
- [installation-recovery.py](installation-recovery.py) - typed recovery receipts, inactive plugin storage outside IDEA discovery, and exact legacy or direct-service login detachment.
- [codex-mcp-registration.py](codex-mcp-registration.py) - collision-safe user-level Codex MCP registration for persistent installations.
- [released_tool_inventory.py](released_tool_inventory.py) - installed public tool inventory and hosted-only invocation admission.
- [installed_acceptance_product.py](installed_acceptance_product.py) - acceptance product model.
- [run-installed-product.py](run-installed-product.py) - installed-product runner.
- [install-local.sh](install-local.sh) and [install-checkout.sh](install-checkout.sh) - packaging shell boundaries.
- [test-installer-entrypoint.py](test-installer-entrypoint.py) - public remote-installer command contract.
- [test-installed-product.sh](test-installed-product.sh) - installed product, hosted catalog, and private entry-point acceptance.
- [run-hosted-change-acceptance.py](run-hosted-change-acceptance.py) - staged broker, CLI, and plugin change workflow in a private native IDE.
- [hosted_read_regression.py](hosted_read_regression.py) - native CLI/provider read regression and bounded continuation checks.
- [hosted_authority_read_regression.py](hosted_authority_read_regression.py) - ordinary private source edits, observed epochs, stale authority refusal, exact restoration, and provider envelope qualification.
- [hosted_concurrent_read.py](hosted_concurrent_read.py) - bounded concurrent reads and structured transport fault/drain witnesses.
- [hosted_budget_read_regression.py](hosted_budget_read_regression.py) - independent caller-grant forwarding across installed query and source reads.
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
