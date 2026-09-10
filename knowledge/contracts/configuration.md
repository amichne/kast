---
type: API Contract
title: Installation configuration
description: Every external configuration input has declared ownership, parsing, defaults, and projection before it can affect a managed runtime.
resource: file://distribution/contract
tags: [configuration, distribution, installation]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationSchema.kt
    symbols: [KastConfigurationSchema]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt
    symbols: [SavedConfigurationIngress]
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Saved configuration is admitted at the CLI boundary, then projected to runtime owners instead of being read ambiently throughout the system.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

See [distribution](../modules/distribution.md).
