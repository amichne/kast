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
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SavedConfigurationIngress.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstalledWorkspaceConfigurationIngress.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationJavaRuntime.kt
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
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpSingleChangeTool.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/SavedConfigurationAdmissionTest.kt
  - path: packaging/configuration-schema.json
  - path: build-policy/configuration-ingress.json
  - path: packaging/install-local.sh
  - path: packaging/test-install-local.py
  - path: build.gradle.kts
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/bootstrap/ModelInputFailure.kt
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/gradle/GradleImportEnvironment.kt
sources:
  - id: openwiki-source-23800b7a30d62f442e3b56ae
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/IdeLifecycleDeployment.kt
  - id: openwiki-source-0934da5590f6da4c7a9cd885
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationConfigurationMigration.kt
  - id: openwiki-source-8f4d86f42434fcc1ce269879
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - id: openwiki-source-db07d240b5fb033a752d32dc
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/installation/ManagedControlAdapter.kt
  - id: openwiki-source-f2c689f11ca37c5559bce314
    resource: repo://cli/src/main/kotlin/io/github/amichne/kast/cli/KastNativeMain.kt
  - id: openwiki-source-fb61cc58d2fa4809ff4d73bb
    resource: repo://cli/src/main/native-launchers/bin/kast-mcp
  - id: openwiki-source-22e272ee16fcff0c22f636ab
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ContainerStateAdmission.kt
  - id: openwiki-source-ecb21af09f4a49f9e40342dd
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagedWorkspaceQuery.kt
  - id: openwiki-source-7cd4eb997a7878eca6390142
    resource: repo://distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - id: openwiki-source-5ec7d945181fa4fe6a508e08
    resource: repo://distribution/cli/src/test/kotlin/io/github/amichne/kast/distribution/cli/InstalledProductRuntimeTest.kt
  - id: openwiki-source-fd4148a6ba46f590f554b6b6
    resource: repo://distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationMetadata.kt
  - id: openwiki-source-a788214182281b80d302305d
    resource: repo://distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/ReadLimitConfigurationTest.kt
  - id: openwiki-source-40ab5c6f8a11b724c001ed82
    resource: repo://distribution/contract/src/test/kotlin/io/github/amichne/kast/distribution/contract/configuration/RetiredRuntimeConfigurationTest.kt
  - id: openwiki-source-d615ad8f008f0449dbc09311
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstalledWorkspaceConfigurationIngress.kt
  - id: openwiki-source-821af660abbe3507ea0b79ee
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedHeadlessInstallation.kt
  - id: openwiki-source-0f233df83b3fe87ab9547161
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SavedConfigurationIngress.kt
  - id: openwiki-source-8677e434adb584035406f54a
    resource: repo://distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
generated: { by: "amp", at: "2026-11-13T00:20:29.143Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-13T03:20:11.225Z
---

# Installation configuration

The Kotlin catalogue owns typed keys, value parsing, defaults, and consuming component ownership. Installed-runtime composition admits saved configuration, then projects it to runtime owners instead of reading it from the ambient environment throughout the system. The installer validates its staged saved file through the same source, resolution, and app-server owner admissions without invoking a retired CLI command. The daemon admits its installed configuration and exact workspace before using the existing-IDE socket. Former CLI semantic and configuration commands are rejected at the private executable ingress. Invalid configuration rejects without starting a worker. The IDE separately retains validated JVM/environment read limits for its project-service lifetime. Process-boundary checks cover daemon-demand failure and rejected former commands without creating runtime/cache directories.

Two checked artifacts enforce the boundary: [configuration-schema.json](../../packaging/configuration-schema.json) describes the external document and [configuration-ingress.json](../../build-policy/configuration-ingress.json) declares permitted ingress owners. Root verification rejects undeclared ambient reads.

Launchers select their pinned installation configuration only when
`KAST_CONFIGURATION_FILE` is absent. An inherited selector, including an older
installation or an intentional alternate, retains its value and environment
provenance; unset it to choose the invoked launcher's default. Strict ingress
still rejects invalid or unavailable selections.

The installed default selects `installation/config/environment` under the
managed Kast root. The selected file must have an ordinary canonical absolute
path. Symlinks in the file or its ancestors reject. Bounded reads keep the
selected configuration's provenance and check file identity again after reading.

`KAST_APP_SERVER_PUBLIC_ENDPOINT` defaults to `private`.
Installation admits only an explicit `private` selection.
It migrates an admitted saved `codex-control` selection to `private` with finite provenance.
Unknown, empty, or explicit shared selections reject before installation effects.
Codex keeps its own App Server and discovery endpoint. Installation does not start a broker.

The installer accepts the saved `KAST_READ_MODEL_MODULES` assignment only from an admitted `0.50.2` installation.
It accepts one ASCII-decimal value from `1` through `2,147,483,646`.
The prior manifest must name that version, the selected installation root, and its own configuration path.
The installer removes this assignment before current configuration admission and keeps its typed migration fact.
Migration rejects duplicates, invalid values, and manifests from other versions.
The runtime catalogue rejects the removed key.

Staging regenerates migrated configuration from current declarations.
Unchanged Control-only settings keep the existing exact-copy path.
Managed preparation preserves `CONFIGURATION_SOURCE_REJECTED`, `CONFIGURATION_RESOLUTION_REJECTED`, and `CONFIGURATION_OWNER_REJECTED` as distinct adapter failures.
The migration record contains only the known key, source version, and admitted count.

See [distribution](../modules/distribution.md).

The `KAST_READ_*` declarations keep parameter identity, admitted values, and provenance through model/epoch capture and semantic budgets. They keep those same facts through native collection, source paging, diagnostic scope enumeration, transport, and provider execution. [Configuration instructions](../../docs/hosted-read-configuration.md) explain activation and paired bounds.

Client exchange time strictly exceeds host connection time. Each provider invocation deadline strictly exceeds client exchange time. Equality in either comparison rejects with `InconsistentBounds`. Semantic configuration can equal host query configuration because semantic admission reserves completion time. Default request diagnostics include the effective policy.

The fixed `broker.kast.schema.maximum_bytes` declaration is 1 MiB, aligned with
the existing broker catalog allowance. It bounds schema qualification's combined
process output independently of semantic request/result limits. The previous
512 KiB policy was internal, not an external schema restriction; it must not
force removal of finite variants or precise schema metadata.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

The native management command uses derived installer inputs, not user configuration settings. Exact repair selects the recorded complete product through `InstalledProductSelection`; it does not cold-stage a Control-only reinstall.

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

Managed admission derives the selected IDEA home from the installed product and workspace generation. It does not discover a user's desktop installation. The launch adapter still validates product metadata and executable containment for supported `262.*` and `263.*` installations; metadata admission is not native qualification.

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
installation. Installation and installed management operations reject alternate root bindings.
`XDG_DATA_HOME` does not select another installation. The root and installer bin
paths keep their HOME-derived proofs in typed request values.

The derived `KAST_INSTALL_PROFILE` accepts only persistent. Session installation
is retired, and checkout session requests reject before Gradle or installation
effects. `KAST_INSTALL_ROOT` and `KAST_BIN_DIR` are derived bindings, not saved
user settings. These legacy adapter bindings do not select another public installation.

The private daemon remains a declared raw-environment ingress owner. Public CLI, MCP, and tool RPC instead share managed request admission. MCP initialization is passive: the first explicit semantic request selects the exact canonical root and its private Host state. Retained reads require an existing record instead of creating a trust grant. These launchers add no Codex App Server request.
The private service-control entry point is also a declared raw-environment ingress owner. It selects the installed release's saved configuration only when no selector was supplied, then delegates registration, passive status, enable, disable, stop, bootstrap, or repair to the existing owners.

Shipped Control launchers select modes in one native executable and need no JVM or bundled JVM libraries.
The launchers pass `HOME` and `TMPDIR` as explicit native system properties when those variables contain values.

## Managed headless selection

`ManagedWorkspaceQuery` selects the installed product locally, without release-server acquisition or desktop fallback.
It rejects an inherited `KAST_MANAGED_HEADLESS_STATE` override. The admitted workspace generation supplies that internal setting and its private HOME.
The private HOME must equal the selected state directory plus `/home`.
The selected IDEA distribution, its bundled JBR, and its assembly record must pass admission before lifecycle or semantic access.
An explicit semantic request can create the exact-root trust record. Discovery, status, and session hooks cannot.

`distribution/managed` owns saved configuration ingress for management and transport consumers.
`InstalledWorkspaceConfigurationIngress` merges the selected installation file with `config/workspaces/<root-digest>/environment`.
It checks physical roots and existing path components before bounded reads. Missing optional files mean absent configuration; unreadable or aliased files reject.
This inspection does not register a workspace. `ContainerPreparationConfiguration` maps its failure into managed request admission.

Gradle uses the private HOME's `.gradle` directory by default.
A saved `state/gradle-home.json` can select an existing canonical directory instead.
Malformed, aliased, or unavailable selections reject; admission does not replace them with a default.
Workspace creation uses resolved `GRADLE_USER_HOME` when supplied. The launch environment keeps inherited credentials; private state is not an OS sandbox.

Durable workspace records retain an absolute, normalized external Gradle path even when that directory disappears.
Managed launch checks its current availability and rejects with `INVALID_GRADLE_HOME` before starting a Host.
Deleting a cache therefore does not revoke workspace ownership or block retirement and uninstall.
Recreating the same canonical directory restores launch eligibility without rewriting the workspace record.

These are source-level contracts. Native qualification and complete product admission remain separate gates; see [release qualification](../distribution/release-candidates-and-promotion.md).

## Imported model inputs

`ModelInputPath` admits a bounded logical path without absolute paths, parent traversal, or control characters.
Model input failures preserve the selected path and a finite reason.
`GradleImportEnvironment` admits explicitly selected variables and executable directories.
It rejects reserved process inputs and excludes unselected ambient values from its identity.
Public evidence contains admitted names and content digests without the raw selected values.
Only the launch adapter receives those raw values through the explicit process projection.
