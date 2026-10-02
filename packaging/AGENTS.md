<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-28 | hash: ffac52372555 -->

# packaging

## Purpose

Owns the public shell installer boundary, shipped offline lifecycle and recovery
helpers, generated configuration ingress, and a small assembled-product check.
Semantic read and change rules belong to their Kotlin owners. Live IDEA
qualification requires separate runtime evidence.

## Key Files

- [install-checkout.sh](install-checkout.sh) and [install-local.sh](install-local.sh) - checkout and staged-product shell adapters.
- [installation-lifecycle.py](installation-lifecycle.py) - selected installation removal and prior retirement when the normal runtime is unavailable.
- [installation-recovery.py](installation-recovery.py) - plugin activation, offline recovery receipts, and upgrade sealing.
- [prune-prior-installations.py](prune-prior-installations.py) - protected historical cleanup and explicit uncertain-entry review.
- [codex-mcp-registration.py](codex-mcp-registration.py) - collision-safe optional Codex MCP registration.
- [configuration_ingress.py](configuration_ingress.py) and [configuration-schema.json](configuration-schema.json) - checked configuration ingress and snapshot.
- [generate-public-query.py](generate-public-query.py) - generated public query source from the authored schema.
- [run-installed-product.py](run-installed-product.py) and [installer_fixture.py](installer_fixture.py) - assembled archive and public installer smoke in an owned temporary root.
- [run-portable-tests.py](run-portable-tests.py) and [run-portable-tests-container.sh](run-portable-tests-container.sh) - offline installer and build-tool test inventory.

## Entry Points

- Public installation begins at root `install.sh`.
- Local checkout installation begins at `install-checkout.sh`.
- The routine Gradle artifact boundary is `installedProductTest`.
- Python `test-*.py` suites run individually and through `run-portable-tests.py`.

## Navigation Hints

- Start with [distribution knowledge](../openwiki/modules/distribution.md), then
  the owning Kotlin contract or the exact installer script.
- For lifecycle and recovery changes, prove selected-installation protection and
  finite failure outcomes in the corresponding focused Python test while these
  shipped helpers remain.
- Do not add semantic queries or native IDEA/Codex probes to packaging tests.
