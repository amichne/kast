---
type: API Contract
title: Installation configuration
description: Every external configuration input has declared ownership, parsing, defaults, and projection before it can affect the broker, installation or existing-IDE request.
resource: file://distribution/contract
tags: [configuration, distribution, installation]
timestamp: 2026-09-21T00:00:00Z
code_sources:
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationCatalogue.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationMetadata.kt
  - path: distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/RetiredRuntimeConfigurationTest.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/InstalledConfigurationAlias.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/SavedConfigurationIngress.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationConfigurationValidation.kt
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
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/SavedConfigurationIngress.kt
    symbols: [SavedConfigurationIngress]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpApproval.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SavedConfigurationAdmissionTest.kt
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Installed-runtime composition admits saved configuration, then projects it to runtime owners instead of reading it ambiently throughout the system. The installer validates its staged saved file through the same source, resolution, and app-server owner admissions without invoking a retired CLI command. The daemon admits its installed configuration and exact workspace before using the existing-IDE socket. Former CLI semantic and configuration commands are rejected at the private executable ingress. Invalid configuration rejects without starting a worker. The IDE separately retains validated JVM/environment read limits for its project-service lifetime. Process-boundary checks cover daemon-demand failure and rejected former commands without creating runtime/cache directories.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

Launchers select their pinned installation configuration only when
`KAST_CONFIGURATION_FILE` is absent. An inherited selector, including an older
installation or an intentional alternate, retains its value and environment
provenance; unset it to choose the invoked launcher's default. Strict ingress
still rejects invalid or unavailable selections.

The exact installation-owned `current/config/environment` alias may resolve to
its manifest-declared immutable sibling under `versions`. Admission requires
the current anchor, canonical target, matching ownership and a nonblocking shared
activation lock. The alias and target identities are checked again after the
pinned configuration read. Other symlinks remain rejected.

`KAST_APP_SERVER_PUBLIC_ENDPOINT` defaults to `private`. Installation admits and
persists an explicit `codex-control` selection through the same broker-owned type;
unknown or empty selections reject. Private routing lets the native Codex daemon
retain its own discovery endpoint. Saved installation configuration remains the
desired state when post-install activation is pending.

See [distribution](../modules/distribution.md).

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

Broker configuration identity remains owner-correlated while coordinator status admits zero workers. The private executable admits only product version inspection; former semantic and configuration commands reject.

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

The existing `KAST_INSTALL_IDEA_HOME` selection also identifies the lifecycle host. Installation saves that home in its environment and a derived `config/selected-ide.json` receipt containing the bundle and real executable (or a finite resolution failure). These are derived observations, not independently configurable launch paths. Explicit opening revalidates product metadata and the executable, allowing any supported `262.*` patch update. Inspection and installation never launch IDEA.

The catalogue rejects removed isolated-runtime setup inputs as `UNKNOWN_KEY` at every source: `KAST_NETWORK_CONFIG`, `KAST_TRUST_DONOR_JAVA_HOME`, `KAST_IDE_CONFIG_HOME`, `KAST_INSTALL_RUNTIME_ARCHIVE`, `KAST_INSTALL_RUNTIME_SHA256`, `KAST_RUNTIME_BASE_URL`, `KAST_LOCAL_RUNTIME_ARCHIVE`, and `KAST_SEMANTIC_RUNTIME_ARCHIVE`. Remove these assignments from saved configuration and the calling environment. The catalogue no longer advertises indexer transport limits or derived JVM settings owned by the retired indexer and workspace importer.

Every installation exposes the complete app-server tool catalog. The installer
regenerates its owned configuration from current declarations on upgrade. User
assignments to retired settings reject with `RETIRED_KEY`; remove them rather
than migrating their values: `KAST_ENABLE_APP_SERVER`, `KAST_APP_SERVER_TOOLS`,
`KAST_ENABLE_LAUNCHD`, `KAST_INSTALL_REFRESH_APP_SERVER`,
`KAST_INSTALL_REPLACE_COMMAND_COLLISIONS`, `KAST_INDEXER_MAX_HEAP`,
`KAST_WORKER_RESIDENT_LIMIT`, `KAST_WORKER_STARTUP_LIMIT`,
`KAST_WORKER_AGGREGATE_MIB`, `KAST_WORKER_NATIVE_MIB`, `KAST_WORKER_GRADLE_MIB`,
`KAST_RUNTIME_ARCHIVE`, `KAST_RUNTIME_STORE`, and `KAST_CACHE_ROOT`.

Installation uses one derived `KAST_INSTALL_PROFILE`: persistent activates the
coordinator and login service; session defers activation while retaining the same
complete payload. The public installer always selects persistent. The checkout
entrypoint derives the profile from its required session or persistent argument.

The private daemon entry point is a declared raw-environment ingress owner. It checks managed readiness inputs and the saved-configuration rejection marker before invoking the existing coordinator configuration admission. Its generated launcher uses the existing derived `KAST_OPTS` JVM boundary; it adds no saved configuration setting. The installed MCP process and its interactive approval helper are also declared ingress owners. They resolve the selected installation and IDEA host from saved configuration for each session; they add no saved configuration setting. After exact Gradle-root discovery, the first valid modern request or legacy initialization starts workspace preparation through that selected host.
The private service-control entry point is also a declared raw-environment ingress owner. It selects the installed release's saved configuration only when no selector was supplied, then delegates registration, enable, disable, stop, repair, or trust enrollment to the existing owners.
