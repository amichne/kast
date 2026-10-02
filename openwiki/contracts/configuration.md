---
type: API Contract
title: Installation configuration
description: Every external configuration input has declared ownership, parsing, defaults, and projection before it can affect the broker, installation or existing-IDE request.
resource: file://distribution/contract
tags: [configuration, distribution, installation]
code_sources:
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - path: install.sh
  - path: packaging/install-checkout.sh
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationCatalogue.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationMetadata.kt
  - path: distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/RetiredRuntimeConfigurationTest.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
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
    symbols: [SavedConfigurationIngressCompositionFailure]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastServiceMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpApproval.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SavedConfigurationAdmissionTest.kt
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
verified:
  - by: openwiki/0.6.1
    at: 2026-10-02T07:23:23.815Z
sources:
  - id: openwiki-source-b49f63bec354bf14b4c28692
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/SavedConfigurationIngress.kt
  - id: openwiki-source-b5958cf441728188e2493589
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ControlInstallationInput.kt
  - id: openwiki-source-211f90f77f217ad3273b0d4f
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-7cd4eb997a7878eca6390142
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - id: openwiki-source-ae4b8d5875d797e7d0c59ae5
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - id: openwiki-source-fd4148a6ba46f590f554b6b6
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationMetadata.kt
  - id: openwiki-source-681368e4942d2fb35f298a68
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/KastConfigurationCatalogue.kt
  - id: openwiki-source-40ab5c6f8a11b724c001ed82
    resource: repo://distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/RetiredRuntimeConfigurationTest.kt
  - id: openwiki-source-03ffc32a0ca502ab67c54b25
    resource: repo://install.sh
  - id: openwiki-source-4070dc53852de69cdff3bd47
    resource: repo://packaging/install-checkout.sh
generated: { by: "codex", at: "2026-10-02T04:13:44.061Z" }
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Installed-runtime composition admits saved configuration, then projects it to runtime owners instead of reading it ambiently throughout the system. The installer validates its staged saved file through the same source, resolution, and app-server owner admissions without invoking a retired CLI command. The daemon admits its installed configuration and exact workspace before using the existing-IDE socket. Former CLI semantic and configuration commands are rejected at the private executable ingress. Invalid configuration rejects without starting a worker. The IDE separately retains validated JVM/environment read limits for its project-service lifetime. Process-boundary checks cover daemon-demand failure and rejected former commands without creating runtime/cache directories.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

Launchers select their pinned installation configuration only when
`KAST_CONFIGURATION_FILE` is absent. An inherited selector, including an older
installation or an intentional alternate, retains its value and environment
provenance; unset it to choose the invoked launcher's default. Strict ingress
still rejects invalid or unavailable selections.

The installed default selects `installation/config/environment` under the
managed Kast root. The selected file must have an ordinary canonical absolute
path. Symlinks in the file or its ancestors reject. Bounded reads retain the
selected configuration's provenance and check file identity again after reading.

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

The native management command uses two derived installer inputs: `KAST_MANAGEMENT_CHANNEL` selects the installed release channel during executable publication, and `KAST_MANAGEMENT_REPORT_PATH` carries the private installer activation report to upgrade and pinned reinstall. They are owned by `:distribution:cli` and are not user configuration settings.

The generated snapshot includes the four `EXECUTION_MAX_*` ceilings for time,
work, results and returned bytes, each defaulting to 2,147,483,646. It also
declares `SOURCE_CONTINUATION_BYTES` (33,554,432 bytes) and
`SOURCE_CONTINUATION_TTL_MILLIS` (600,000 ms), alongside the transport backlog
and connection limits. Refresh the snapshot from
`:cli:generateConfigurationCatalogue`; `verifyConfigurationIngress` requires
byte-for-byte agreement with that owner-generated output.

Broker configuration identity remains owner-correlated while coordinator status admits zero workers. The private executable admits only product version inspection; former semantic and configuration commands reject.

The default host query limit is 30,000 ms, with 31,000 ms for the host connection and 32,000 ms for the client exchange. This gives cached Gradle model capture time before semantic search while retaining strict outer deadlines. At semantic entry the host derives smaller positive semantic and diagnostic-scope allowances from remaining request time, preserving the configured policy separately in diagnostics. See the deadline admission rules in the read configuration guide.

`HOST_REFERENCE_ENTRIES` and `HOST_REFERENCE_BYTES` bound the project-owned compact-reference table. Their defaults are 16,384 entries and 33,554,432 UTF-8 bytes; both are positive typed read limits. Capacity preserves a valid inline representation instead of evicting current-epoch handles.

`DISCOVERY_FILES` separately bounds cheap scoped lexical file enumeration before
compiler refinement. Query continuation retention has independent typed limits:
`QUERY_CHECKPOINT_BYTES` bounds one checkpoint, and `QUERY_CONTINUATION_ENTRIES`,
`QUERY_CONTINUATION_BYTES`, `QUERY_CONTINUATION_TTL_MILLIS` bound each project-owned
execution/output store. Both stores apply the same policy independently, so the
combined retained-state ceiling is twice the configured per-store byte bound.
These keys are declared in the installation catalogue and generated snapshot.

`HOST_ACCEPT_BACKLOG` (64) bounds native pending connections;
`HOST_CONNECTIONS` (16) bounds concurrently served frames; `HOST_READERS` (2)
separately bounds overlapping semantic invocations until computation drains.
The [native qualification](../../docs/reviews/concurrent-semantic-reads.md) motivates the reader default.
Saturation returns a finite admission rejection when a connection
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

Kast has one installation at `$HOME/.local/share/kast/installation`.
Control and Host have independent artifact and effect ownership within that
installation. Installation and management ingress reject alternate root bindings;
`XDG_DATA_HOME` does not select another installation. The root and installer bin
paths retain their HOME-derived proofs in typed request values.

The derived `KAST_INSTALL_PROFILE` accepts only persistent. Session installation
is retired, and checkout session requests reject before Gradle or installation
effects. `KAST_INSTALL_ROOT` and `KAST_BIN_DIR` are derived bindings, not saved
user settings. Explicit cold staging defers Control activation through the
existing lifecycle owner; it does not create another installation.

The private daemon entry point is a declared raw-environment ingress owner. It checks managed readiness inputs and the saved-configuration rejection marker before invoking the existing coordinator configuration admission. Its generated launcher uses the existing derived `KAST_OPTS` JVM boundary; it adds no saved configuration setting. The installed MCP process is also a declared ingress owner. It resolves the selected installation and IDEA host from saved configuration for each session; it adds no saved configuration setting. After exact Gradle-root discovery, the first valid modern request or legacy initialization starts workspace preparation through that selected host. Direct MCP file refresh and one-call change reuse the session's selected host and exact root. The harness-neutral tool RPC uses that same direct tool composition and selected configuration. Its process command adds no saved configuration setting or Codex App Server request.
The private service-control entry point is also a declared raw-environment ingress owner. It selects the installed release's saved configuration only when no selector was supplied, then delegates registration, passive status, enable, disable, stop, bootstrap, repair, or trust enrollment to the existing owners.

Component selection belongs to installation ingress: `KAST_INSTALL_CONTROL_ONLY` selects a control-only upgrade and `KAST_HOST_VERSION` selects host provenance only for a paired or host installation. Local checkout routing retains `KAST_LOCAL_COMPONENT`, `KAST_LOCAL_PROFILE`, and `KAST_LOCAL_HOST_RELEASE_RECORD`. Control-only replacement retains the admitted saved configuration and workspace registration; it starts fresh control state and session ownership.

Exact-version reinstall cold-stages only Control with `--stage-only`, then the existing fenced lifecycle owner activates it. Staging requires no Host artifact and cannot be combined with the bounded `--control-only` upgrade, whose activation and live host checks are mandatory.

The native private-installer boundary removes inherited Host-version and installation selectors, retains ordinary process inputs, and supplies its owned report destination. After admitting installation ownership, install and uninstall explicitly forward that selected root; a foreign ambient root cannot redirect those effects. The recorded public command destination remains a separate publication receipt fact and never selects another installation.
