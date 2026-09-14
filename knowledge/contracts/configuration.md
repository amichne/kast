---
type: API Contract
title: Installation configuration
description: Every external configuration input has declared ownership, parsing, defaults, and projection before it can affect the broker, installation or existing-IDE request.
resource: file://distribution/contract
tags: [configuration, distribution, installation]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerOperationalLimits.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/provider/KastSchemaOutputBudgetTest.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
    symbols: [ReadLimits, ReadLimitParameter]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeReadConfiguration.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/HostedReadConfiguration.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols: [ConfigurationSchemaDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols: [InstalledConfigurationSchema]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/ConfigurationInspection.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/ConfigurationInspectionTest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt
    symbols: [SavedConfigurationIngress]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SavedConfigurationAdmissionTest.kt
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Installed-runtime composition admits saved configuration, then projects it to runtime owners instead of reading it ambiently throughout the system. Mutation commands retain this admission before connecting to the existing IDE. The default semantic read entry point selects the existing IDE before installed composition, admits saved/environment configuration, and then opens its socket. Invalid configuration returns a configuration rejection without starting a worker. The IDE separately retains validated JVM/environment read limits for its project-service lifetime. The process-boundary checks cover both paths and verify that rejection creates no runtime/cache directories.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

See [distribution](../modules/distribution.md).

`kast config --help` (or `-h`) lists schema, show, explain and validate before
loading configuration. Help remains available when saved or process configuration
is invalid; it starts no runtime and writes its text to stdout.

The `KAST_READ_*` declarations retain parameter identity, admitted values and provenance across model/epoch capture, semantic budgets, native collection, source paging, diagnostic scope enumeration, transport and provider execution. [Configuration instructions](../../docs/hosted-read-configuration.md) explain activation and paired bounds. Client exchange time strictly exceeds host connection time, and each provider invocation deadline strictly exceeds client exchange time; equality rejects with `InconsistentBounds`. Semantic configuration may equal host query configuration because semantic admission reserves completion time. Default request diagnostics include the effective policy.

The fixed `broker.kast.schema.maximum_bytes` declaration is 1 MiB, aligned with
the existing broker catalog allowance. It bounds schema qualification's combined
process output independently of semantic request/result limits. The previous
512 KiB policy was internal, not an external schema restriction; it must not
force removal of finite variants or precise schema metadata.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

The generated snapshot includes the four `EXECUTION_MAX_*` ceilings for time,
work, results and returned bytes, each defaulting to 2,147,483,646. It also
declares `SOURCE_CONTINUATION_BYTES` (33,554,432 bytes) and
`SOURCE_CONTINUATION_TTL_MILLIS` (600,000 ms), alongside the transport backlog
and connection limits. Refresh the snapshot from
`:cli:generateConfigurationCatalogue`; `verifyConfigurationIngress` requires
byte-for-byte agreement with that owner-generated output.

Bare installed CLI composition fails closed on rejected saved configuration. Its passive product response identifies `existing_ide` authority and root discovery rather than inventing a worker/bootstrap observation. Broker configuration identity remains owner-correlated while coordinator status admits zero workers.

The default host query limit is 4,000 ms. At semantic entry the host derives smaller positive semantic and diagnostic-scope allowances from remaining request time, preserving the configured policy separately in diagnostics. See the deadline admission rules in the read configuration guide.

`HOST_REFERENCE_ENTRIES` and `HOST_REFERENCE_BYTES` bound the project-owned compact-reference table. Their defaults are 16,384 entries and 33,554,432 UTF-8 bytes; both are positive typed read limits. Capacity preserves a valid inline representation instead of evicting current-epoch handles.

`DISCOVERY_FILES` separately bounds cheap scoped lexical file enumeration before
compiler refinement. Query continuation retention has independent typed limits:
`QUERY_CHECKPOINT_BYTES` bounds one checkpoint, and `QUERY_CONTINUATION_ENTRIES`,
`QUERY_CONTINUATION_BYTES`, `QUERY_CONTINUATION_TTL_MILLIS` bound each project-owned
execution/output store. Both stores apply the same policy independently, so the
combined retained-state ceiling is twice the configured per-store byte bound.
These keys are declared in the installation catalogue and generated snapshot.

`HOST_ACCEPT_BACKLOG` (64) bounds native pending connections;
`HOST_CONNECTIONS` (16) bounds concurrently served frames. Semantic work remains
serialized. Saturation returns a finite admission rejection when a connection
has reached the application; the OS backlog is a separate finite capacity.
