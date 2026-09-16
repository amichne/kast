---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags: [distribution, configuration, packaging, release]
timestamp: 2026-09-14T00:00:00Z
code_sources:
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationChild.kt
    symbols: [executeInstallationChild]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRegistryObservation.kt
  - path: packaging/hosted_repair_time_observation.py
  - path: packaging/hosted_repair_budget_regression.py
  - path: packaging/hosted_transport_observation.py
  - path: change/intellij/src/nativeFixture/kotlin/io/github/amichne/kast/fixtureprobe/ProbeOwnedSourceRescan.kt
  - path: change/intellij/src/nativeFixture/kotlin/io/github/amichne/kast/fixtureprobe/ProbeSetupReadiness.kt
  - path: change/intellij/src/nativeFixture/kotlin/io/github/amichne/kast/fixtureprobe/ProbeSetupReadinessDocument.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerInstallationState.kt
    symbols: [BrokerInstallationState]
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/acceptance/hostedchange/NativeHostedReadMain.kt
  - path: packaging/installed_codex_lifecycle.py
  - path: packaging/released_coordinator_acceptance.py
  - path: packaging/released_session_acceptance.py
  - path: packaging/released_upgrade_acceptance.py
  - path: packaging/released_acceptance_product.py
  - path: packaging/released_tool_inventory.py
  - path: packaging/hosted_raw_symbol_regression.py
  - path: packaging/released_payload_identity.py
  - path: packaging/run-hosted-change-acceptance.py
  - path: packaging/hosted_read_regression.py
  - path: packaging/hosted_authority_read_regression.py
  - path: packaging/hosted_concurrent_read.py
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/ControlDistributionLimits.kt
    symbols: [ControlDistributionLimits]
  - path: distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/configuration/ConfigurationSchemaDocument.kt
    symbols: [ConfigurationSchemaDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/configuration/InstalledConfigurationSchema.kt
    symbols: [InstalledConfigurationSchema]
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ManagedInstallationOwnedTree.kt
    symbols: [ManagedInstallationOwnedTree]
  - path: packaging/configuration-schema.json
  - path: packaging/installation-lifecycle.py
  - path: packaging/installation-recovery.py
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationRecoveryReceipt.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
  - path: install.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols: [InstallationWorkflow]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols: [InstallationRequest, AppServerTools]
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: distribution/release/plugin-release.gradle.kts
  - path: packaging/test-installed-product.sh
  - path: .github/scripts/release/build-assets.sh
  - path: .github/scripts/release/publish-release.sh
---

# Distribution and packaging

Distribution contracts own configuration keys, defaults, owners, operational limits, network policy, runtime identity, and bootstrap outcomes. Managed adapters materialize only admitted archives, endpoints, trust stores, and owned installation trees.

Root and packaging scripts orchestrate checkout installation, persistent lifecycle, release layout, and acceptance. Stable releases and local checkout installations include a hosted-plugin ZIP named for the IDEA release line (`idea-262.zip`). Local installation builds the control product and matching hosted plugin before staging their checksums. The public installer verifies its checksum, release version, plugin identity, and descriptor release line before atomically replacing only the Kast directory under IDEA's user plugin root; dry-run validates the archive without writing plugin state. The checked [configuration schema](../../packaging/configuration-schema.json) is a public boundary and must remain aligned with the Kotlin catalogue.

The public installer reports the selected IDEA product version and build before
fetching release-line-specific plugin bytes. An absent matching plugin is a
fail-closed compatibility result and precedes installation effects. Interactive
public installation explains app-server tooling and asks whether to enable the
per-user login LaunchAgent; `--no-interactive` skips that read and defaults the
LaunchAgent off unless its existing configuration switch explicitly enables it.

Hosted-only schema-2 installations retire their coordinator without invoking
the retired isolated-workspace `stop` command. Legacy schema-1 installations
retain that obligation. Lifecycle rejection reports identify the bounded stage
and outcome, including unresolved worker receipts; failed retirement preserves
the existing transition journal and state. Successful plugin installation still
requires the separately reported IDEA restart. Retired CLI start/stop guidance
identifies the IDE lifecycle as user-managed rather than claiming a replacement
operation.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, command qualification and App Server enablement retain distinct success, nonzero exit, deadline, I/O and interruption observations. New-payload admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths. Prior admission or retirement failure triggers automatic replacement using the exact installation-derived launchd labels and processes whose executable or argument path belongs to that installation. Failed prior validation cannot veto the upgrade. Replacement has its own bounded stage and outcome. Damaged same-version payloads and recovery bundles move aside before restaging; unrelated paths are not recursively deleted.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. The shell bootstrap, installed lifecycle, build verifier, and Kotlin owner are checked for the same entry limit, and release layout verification rejects a product outside that bound before publication. Upgrade admission uses the new, checksum-verified lifecycle implementation to inspect the prior installation. If inspection or ordinary retirement fails, replacement proceeds without executing the prior payload. This retains the resource limits while preventing copied limits from drifting below the product that the build produced.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics retain the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

Before activation, `prepareInstallationRecovery` saves a typed receipt and an offline Python bundle outside the immutable version payload. Active plugin bytes alone occupy `plugins/kast-ide-hosted`; candidate, baseline and detached copies remain in the private sibling `.kast-plugin-recovery` on the same filesystem. Recovery admits exactly the legacy discovery-root layout or this retained layout, with one shared token and inode ownership proofs. The installer disconnects the prior-installation recovery chain, so old or corrupt receipts cannot block the new plugin. Standalone recovery on retained historical receipts still migrates receipt-listed legacy copies through the exact prior-installation chain; it saves the updated recovery script and location intent before atomic renames so interruption can resume. Standalone recovery rejects missing, cyclic, oversized or corrupt chains and foreign replacement identities. Installation replaces unusable target recovery metadata and prepares a fresh receipt instead. Only mutable recovery metadata and owned plugin paths change: prior payloads, configuration, installation manifests and workspace registries remain unchanged. `installation-recovery.py detach` fences launches, detaches matching command links and receipted plugin directories, and retains previous plugin backups outside discovery. It executes retirement only after full payload admission. Verified retired state is quarantined; uncertain processes and journals remain preserved and produce `DetachedWithUnresolvedState`. A missing plugin ownership witness cannot produce a clean result. The standalone `prepare` operation supports older installations without requiring their executable to run. Python and JVM installation transitions use compatible POSIX record locks.

The staged Kotlin installer selects default tools directly from the canonical agent catalog. The shell bootstrap preserves an explicit selection and supplies no copied default list. Explicit selections must contain current, unique tool names; installation retains their admitted definitions in canonical catalog order before writing configuration.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

`assembleRelease` ships exactly the control tarball and matching hosted-plugin ZIP with checksums. `GenerateControlMetadataTask` derives `ide-host.json` from the actual plugin bytes and build identities; installation verifies the exact name, version, length and digest. No semantic-runtime manifest, isolated indexer payload or topology store is shipped. Installation manifests use schema 2 and `hostedPluginSha256`; lifecycle inspection still admits historical schema-1 records so old owned installations can be retired safely.

Release asset construction, checksums, SBOM inventory, and publication agree on the control and plugin pair. Native acceptance retains private homes and owned processes without creating isolated runtime caches or worker payload stores.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.

The generated catalogue also declares the four execution ceilings, host backlog
and connection capacity, and native source continuation byte/TTL limits.
`:cli:generateConfigurationCatalogue` owns the snapshot;
`verifyConfigurationIngress` checks its exact agreement with the Kotlin owners.
The [configuration contract](../contracts/configuration.md) records the defaults.

Installed read acceptance uses the same staged CLI, provider and hosted-plugin
identity as the mutation fixture. It records the base matrix, bounded transport
faults, independent caller grants and ordinary-edit authority transitions before
mutation starts. Exact source restoration and observed readiness are required.
The repair matrix emits bounded 10/20-second request receipts with the actual
configured default, operator ceiling, admitted grant, clamp causes and round-trip
duration. A bounded single-request native log window separately retains ordered
admission, model, semantic, freshness and detachment durations, the actual grant
and completion reserve, plus connection admission and release observations. It
requires one completed connection and one same-authority semantic receipt; extra,
missing or contradictory evidence fails qualification. Executor tests force
deadline behavior independently. Native readiness rejects a
saved/document image mismatch even when the IDE reports saved and committed.
Deterministic fixture tests and schema checks do not themselves qualify a native
IDE run; [hosted query qualification](../flows/hosted-query.md) keeps those evidence
boundaries separate.

The native acceptance runner also accepts `--release-assets` and `--release-version` in place of source-built `--product` and `--plugin`. This mode requires a clean checkout at the exact version tag and a harness carrying that source commit. It invokes the tagged public `install.sh` with original checksum-bound control and plugin archives in an exclusively owned fixture. It verifies the checksum-derived installed version directory, manifest inventory, and original archive file bytes, then routes CLI and provider calls through the installed `bin/kast-complete`. The hosted plugin stays in the installer's private JetBrains plugin directory; only the separately identified test probe is added. Login-service and App Server activation are disabled during installation. This admission mode alone proves neither native behavior nor upgrade or persistent-session behavior; those require the corresponding completed runtime receipts. Temporary fake-installer tests qualify the admission boundary only.

Released-mode admission also reads the installed schema through that wrapper and
verifies all 13 advertised tools against their canonical operation IDs and the
11-tool saved default selection. Those defaults contain eight read tools and
three change tools. The native read harness explicitly selects ten read tools,
including raw symbol discovery and inspection, and excludes `change_plan` even
though planning has a read effect. The two raw-symbol cases preserve an issued
candidate through compiler refinement and are invoked for both CLI and provider
surfaces. Adding those cases does not change installed production defaults;
fixture wiring and inventory admission do not establish their native result.

Released mode checks two fresh noninteractive Bash sessions without reading startup files: command resolution, exact version, saved runtime configuration, and installation identity. Optional `--previous-release-assets` and `--previous-release-version` first install the immediately preceding patch through the same tagged target installer, then register the owned empty workspace through that prior wrapper. The upgrade requires completed prior admission, retirement, configuration validation and command qualification observations, unchanged prior payload/configuration, and exact populated workspace-registry retention. Original archives and invocation output digests remain bound to the receipt. These child-shell observations do not qualify login-service activation, a persistent coordinator, or stock Codex UI; those remain explicit runtime gates.

Released native qualification also requires an explicitly admitted Codex executable and
the original installed `kast-complete` and `kast-codex-complete` wrappers. The shared
installed coordinator check observes matched private ownership before attachment,
real initialize and thread-start responses, clean parent stdio closure, a prepared
host after detach, and successful service disable in a finally block. Tool selection
comes from the admitted saved inventory. Its bounded receipt retains hashes and
closed observations; stock desktop UI remains unqualified. This is harness capability,
not evidence that a particular released version passed the live check.

Passive runtime identity inspection emits accepted inventory counters only to an
explicit typed diagnostic sink. Coordinator startup retains its existing success
report, and rejected inventory admission retains bounded finite stderr evidence.
The original-release session helper requires the current target's successful
`config show` and `config explain` calls to keep stderr empty. It passes the owned
JVM home/temp options through launcher `JAVA_OPTS`, without filtering stderr or
relaxing diagnostics. Previous-release sessions retain their historical stderr
as evidence so an adjacent upgrade can qualify the repaired target.

The source-built Codex host acceptance now selects the canonical control endpoint
and requires matched ownership, mode 0600, live launchd observation, native protocol
and catalog readiness, stock daemon version discovery, fresh thread creation and
owned cleanup. Its schema-2 receipt retains bounded status hashes and distinct
unqualified Desktop evidence. Original-release acceptance explicitly selects the
private compatibility policy. These receipts do not prove a model-driven tool
invocation through the existing IDEA runtime.

Disposable native fixture readiness explicitly marks its fixed, canonical
`src/main/kotlin` directory and `Fixture.kt` file for a nonrecursive rescan before
the existing cached-root refresh. The platform can propagate dirty flags to ancestors. The receipt retains
the selected dirty-mark scope and completion separately from refresh and native-task
drain evidence. This fixture lifecycle effect makes direct-child test file creation,
deletion and fixture content restoration observable without depending on watcher intake; semantic queries do not
invoke it. A quiet readiness receipt still does not prove a future epoch is stable.

Workspace registry retention is best-effort during replacement. Its bounded
`kast_installation_registry` observation preserves the exact retention outcome.
Rejected source registries stay in the prior installation; fresh sessions register
their canonical workspace automatically. Failure to stop an exact prior service,
filesystem failure, and interruption remain explicit failures, rather than being
reported as successful retirement.
