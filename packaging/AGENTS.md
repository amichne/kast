---
type: Module Guide
title: "Kast packaging"
description: "Owns installer adapters, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts."
resource: "repo://packaging"
tags: [kast, navigation]
timestamp: "2026-10-15T01:07:09Z"
source_revision: 5a82727f790d365936682699502ee32a792b3634
code_sources:
  - path: packaging/generate-public-query.py
  - path: packaging/host-installation.py
  - path: packaging/install-checkout.sh
  - path: packaging/install-local.sh
  - path: packaging/installation-lifecycle.py
  - path: packaging/installation-recovery.py
  - path: packaging/run-installed-product.py
  - path: packaging/run-portable-tests.py
  - path: packaging/test-host-installation.py
  - path: packaging/test-installer-entrypoint.py
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-15 | hash: 76d6f8654970 -->

# packaging

## Purpose

Owns installer adapters, offline lifecycle helpers, contract generation, and assembled-product acceptance scripts.

## Key Files

- [install-checkout.sh](install-checkout.sh) - checkout installation with selected IDEA JBR admission.
- [install-local.sh](install-local.sh) - staged-product installation.
- [host-installation.py](host-installation.py) - Host plugin installation and receipts.
- [installation-lifecycle.py](installation-lifecycle.py) - selected-installation retirement and removal.
- [installation-recovery.py](installation-recovery.py) - Control recovery and update sealing.
- [generate-public-query.py](generate-public-query.py) - Kotlin and harness projections from the authored public schema.
- [run-installed-product.py](run-installed-product.py) - archive and installer smoke in an owned temporary root.
- [run-portable-tests.py](run-portable-tests.py) - the offline packaging test inventory.

## Subdirectories

- [topology/](topology/) - explicit Git byte attribution for supplied historical paths.

## Entry Points

- Public installation starts at the root `install.sh`.
- For offline packaging tests, run `python3 packaging/run-portable-tests.py` from the repository root.
- The Gradle task `installedProductTest` exercises the assembled product.
- Native reset scripts need the disposable installation setup stated in their own arguments and documentation.
- [test-host-installation.py](test-host-installation.py) - Host target and receipt protection.
- [test-installer-entrypoint.py](test-installer-entrypoint.py) - published-channel selection and installer ingress.
- For the Kast plugin session hook, run `./gradlew agentSessionSetupTest`. Its locked uv environment stays in the build directory.

## Navigation Hints

- For a helper change, start with its corresponding `test-*.py` file and selected-installation rejection cases.
- For generated syntax, change the authored schema and generator together, then examine generation parity.
- Keep semantic rules in their Kotlin owners. Packaging tests do not establish live IDEA or Codex behavior.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
