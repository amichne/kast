---
type: Kotlin Module Group
title: Distribution and packaging
description: Typed configuration and runtime identity contracts constrain managed installation effects, release assembly, and acceptance harnesses.
resource: file://distribution
tags: [distribution, configuration, packaging, release]
timestamp: 2026-09-28T00:00:00Z
code_sources:
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/KastManagementMain.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/IntegrationRegistration.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementLifecycle.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ManagementStatus.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PassiveRuntimeObservation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/OneShotObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/CoordinatorControl.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/KastDaemonMain.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/McpWorkspaceOperationClient.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/McpSingleChangeTool.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationTrustEnrollment.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/PackagedProviderCatalog.kt
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/ControlDistributionAdmissionMain.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/SelectedIdeInstallation.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/PriorInstallationPreparation.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationChild.kt
    symbols: [executeInstallationChild]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorInstallationReplacement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/PriorRetirement.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationDaemonUpgrade.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRegistryObservation.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/BrokerInstallationState.kt
    symbols: [BrokerInstallationState]
  - path: packaging/run-portable-tests.py
  - path: packaging/run-portable-tests-container.sh
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
  - path: packaging/prune-prior-installations.py
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/InstallationRecoveryReceipt.kt
  - path: distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/ControlPayloadInventory.kt
  - path: install.sh
  - path: packaging/codex-mcp-registration.py
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/direct/KastDirectToolSession.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/OneShotInvocationRecord.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/McpWorkspaceOperationClient.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
    symbols: [InstallationWorkflow]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationRequest.kt
    symbols: [InstallationRequest, InstallationProfile]
  - path: build-logic/src/main/kotlin/support/tasks/control/GenerateControlMetadataTask.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: distribution/release/resolve_version.py
    symbols: [observe_releases, published_versions]
  - path: distribution/release/run_product_gate.py
    symbols: [latest_version, run_gate]
  - path: .github/scripts/ci/routine_gate.py
  - path: .github/scripts/ci/validation_scope.py
  - path: .github/scripts/ci/required_checks.py
  - path: .github/scripts/ci/verify-checks.py
  - path: .githooks/pre-push
  - path: distribution/release/plugin-release.gradle.kts
  - path: packaging/installer_fixture.py
  - path: packaging/run-installed-product.py
  - path: .github/scripts/release/build-assets.sh
  - path: .github/scripts/release/ci-candidate.py
  - path: .github/scripts/release/publish-release.sh
  - path: .github/scripts/release/publish-developer.sh
  - path: .github/workflows/ci.yml
  - path: .github/workflows/docs.yml
  - path: .github/workflows/release.yml
  - path: .github/workflows/developer-release.yml
---

# Distribution and packaging

The `distribution:cli` module builds the native public `kast` management executable with Clikt and GraalVM. It reads a separate management ownership receipt and retains the selected public executable path across upgrades. The executable exposes installation status, version, supplied harness registration, upgrade, and uninstall. The operational JVM CLI and transport launchers remain private within the managed payload. Installer path preflight runs before service retirement; executable publication follows payload and IDEA plugin activation. The release manifest qualifies bundled integration bytes before registration. Status reads installation receipts without starting Java or preparing a workspace. The running coordinator supplies a bounded passive projection for loaded version, ready workspaces, and live connection count. One-shot tool RPC calls publish a bounded process identity record while executing; native status counts only records whose exact process incarnation remains live. Unavailable observations remain distinct from verified empty results.

Distribution contracts own configuration keys, defaults, owners, operational limits, runtime identity, and bootstrap outcomes. Managed adapters own installation trees, recovery receipts, selected IDE discovery, and endpoints. The retired isolated runtime downloader, archive store, heap observer, and network/trust-store bootstrap have been removed; IDEA owns its import environment and trust configuration.

Root and packaging scripts orchestrate checkout installation, persistent lifecycle, release layout, and artifact checking. Stable releases and local checkout installations include a hosted-plugin ZIP named for the IDEA release line (`idea-262.zip`). Local installation builds the control product and matching hosted plugin before staging their checksums. The public installer verifies its checksum, release version, plugin identity, and descriptor release line before atomically replacing only the Kast directory under IDEA's user plugin root; dry-run validates the archive without writing plugin state. The assembled-product check verifies artifact identity, required launchers including the native management command, and one real session installation in an owned temporary root. The checked [configuration schema](../../packaging/configuration-schema.json) is a public boundary and must remain aligned with the Kotlin catalogue.

The control product also includes `kast-tool-rpc`. Installation retains its configured `kast-tool-rpc-complete` wrapper inside the selected version and retires owned external command links. The one-shot catalog and call interface is shared by Copilot CLI and Pi extensions without using an MCP connection.

CI selects checks on Linux before allocating product runners. Independent public MDX pages and Mintlify navigation run only documentation validation, for both pull requests and complete before/head push ranges. Installer-linked pages and the generated callable reference retain product, portable, and documentation checks. Conventional Kotlin sources and packaging inputs retain the product and portable checks without Mintlify validation or the separate Gradle graph-membership dry run. Build wiring, unclassified paths, uncertain diffs, and manual CI dispatch retain every check. The stable completion job requires every selected job to succeed and permits only explicitly planned skips. Documentation validation is a reusable workflow with its manual dispatch retained. When product validation is selected, main CI runs preflight checks, then builds and retains one exact next-patch release candidate with the product gate at that version. Full pull-request CI and pre-push fetch the published release catalog anew for each gate invocation, select its highest stable semantic version, and pass that version to the checkout build. Missing release authority rejects without a local-tag or placeholder fallback. Exact release-candidate builds retain their explicitly resolved candidate version. CI retains Gradle profile reports from full pull-request gates and main candidate builds for task timing review. The product job restores Gradle's local task-output cache; main and same-repository pull requests save new entries, while fork pull requests only restore entries. Release reuses the main candidate only when its version and source revision match the requested release, the producing main-push CI run completed successfully, and the asset inventory, checksums, and SBOM source/archive identities validate. An active exact-source main CI run yields `PENDING` when no successful exact candidate is available; the release attempt exits before fallback construction and can be retried after CI completes. Producer state is observed before artifacts so a run completing between observations may supply its candidate without a rebuild. This is conservative observation, not a lock against future runs. A missing candidate follows the existing exact-version build gate only when no active producer was observed; incomplete observations, unsupported workflow states, and invalid candidates reject. Minor and major releases use that build path unless a matching candidate exists. The release workflow retains the admitted candidate before publishing.

An explicitly dispatched developer workflow runs only from `main` and builds
that dispatch's exact commit through the
same product gate with a unique `0.0.<build>` version. Its read-only build job
retains the candidate; a separate trusted publication job validates the asset
inventory, checksums, and SBOM source identity before creating an immutable
developer prerelease. The public `developer-latest` branch contains a
mutable pointer to that exact prerelease and source revision. The installer
accepts `--developer-latest` to resolve the pointer before downloading and
verifying the versioned control and IDEA plugin assets. Stable release version
resolution ignores developer tags.

The public installer reports the selected IDEA product version and build before
fetching release-line-specific plugin bytes. An absent matching plugin is a
fail-closed compatibility result and precedes installation effects. Public installation enables the app-server suite and defaults the per-user login
LaunchAgent on without a prompt. Persistent installation asks in a terminal whether to register a user-level Codex MCP entry. Explicit register and skip flags bypass that prompt; non-interactive installation defaults to registration for compatibility. Skipping registration does not inspect or mutate Codex configuration and still installs the MCP launcher. Command collisions require explicit `--force` or manual removal.
Local session installation retains the complete payload and defers service activation.

Hosted-only schema-2 installations retire their coordinator without invoking
the retired isolated-workspace `stop` command. Legacy schema-1 installations
retain that obligation. Lifecycle rejection reports identify the bounded stage
and outcome, including unresolved worker receipts; failed retirement preserves
the existing transition journal and state. Successful plugin installation still
requires the separately reported IDEA restart. Retired CLI start/stop guidance
does not claim authority to stop IDEA. The installed coordinator prepares workspaces for semantic demand. The explicit `workspace_lifecycle` tool remains available to the agent; the duplicate `workspace lifecycle` CLI command is retired.

Installation defaults to a private coordinator endpoint and persists an explicitly
selected public endpoint policy. Candidate configuration and executable qualification
precede prior-service retirement. Committed installation and service activation have
separate outcomes: ready, not requested, or pending with a finite child-process
failure and a resume command. Pending activation does not prevent the shell bootstrap
from installing the verified IDEA plugin. Saved configuration and installed launchers
retain the desired state; the private `kast-service enable` action resumes
service reconciliation without repeating installation. Workspace registration
is an explicit `kast-service register` action. The pending report does not claim service readiness.

After candidate qualification and recovery preparation, an ordinary upgrade
admits the prior retirement command and asks the selected daemon for an exact
update seal. A proven absent daemon needs no seal. Active daemon blockers or
unproven service state reject before retirement and preserve the current links.
The installer commits a sealed request before invoking the prior service's
disable control, so a failed or mismatched commit cannot stop that service.
New releases use the private `kast-service` executable for activation and
retirement; admitted older releases retain their public CLI retirement command.
If disable fails after commit, the same candidate can resume retirement while
the exact prior daemon remains reachable and reports its committed request.
The upgrade result retains finite blockers and daemon rejection causes.

Installation child processes emit `kast_installation` records by default with a closed stage and outcome. Prior admission, retirement, configuration validation, candidate and activated-command qualification, and App Server enablement retain distinct success, nonzero exit, deadline, I/O and interruption observations. New-payload admission remains authoritative; these records do not contain command arguments, environment values or filesystem paths. Ordinary installation rejects a failed prior admission or retirement with a stage-specific failure and preserves the selected release. An untrusted same-version payload or recovery receipt likewise rejects without replacement. Explicit `--force` remains a separate reset operation.

`ControlDistributionLimits` owns the maximum verified control-product entry count and manifest size used by staged installation and runtime identity admission. The shell bootstrap, installed lifecycle, build verifier, and Kotlin owner are checked for the same entry limit, and release layout verification rejects a product outside that bound before publication. Upgrade admission uses the new, checksum-verified lifecycle implementation to inspect the prior installation. Failed inspection or retirement rejects the upgrade. This retains the resource limits while preventing copied limits from drifting below the product that the build produced.

`ControlPayloadInventory` counts paths globally, including the three payload roots, before sorting or hashing. Installer and broker use that same admission; `verifyReleaseRuntimeAdmission` exercises the runtime identity owner on the actual staged control product. Limit diagnostics retain the resource and observed lower bound. The hosted-plugin ZIP has a separate archive budget.

Before prior-service retirement and activation, `prepareInstallationRecovery` validates or saves a typed receipt and an offline Python bundle outside the immutable version payload. Ordinary upgrades retain the exact prior-installation recovery chain until the public installer completes plugin activation and Codex registration; corrupt candidate metadata rejects before stopping the selected release. Active plugin bytes alone occupy `plugins/kast-ide-hosted`; candidate, baseline and detached copies remain in the private sibling `.kast-plugin-recovery` on the same filesystem. Recovery admits exactly the legacy discovery-root layout or this retained layout, with one shared token and inode ownership proofs. Standalone recovery on retained historical receipts migrates receipt-listed legacy copies through the exact prior-installation chain; it saves the updated recovery script and location intent before atomic renames so interruption can resume. Standalone recovery rejects missing, cyclic, oversized or corrupt chains and foreign replacement identities. After successful activation, the installer exit trap validates the selected plugin and recovery chain, seals the selected receipt so it no longer depends on the prior payload, then asks the checksum-bound lifecycle helper to admit every version directory and retire unselected payloads. A failed effect retains prior evidence and reports an incomplete upgrade. `installation-recovery.py detach` fences launches, detaches matching command links and receipted plugin directories, and retains previous plugin backups outside discovery. It executes retirement only after full payload admission. Verified retired state is quarantined; uncertain processes and journals remain preserved and produce `DetachedWithUnresolvedState`. A missing plugin ownership witness cannot produce a clean result. The standalone `prepare` operation supports older installations without requiring their executable to run. Python and JVM installation transitions use compatible POSIX record locks.

`install.sh --force` uses the same verified release path with explicit reset authority.
Under the activation lock it fences and retires the selected and same-version target
services, removes same-user transport aliases and the exact derived upstream socket
directory, then moves payload/state and recovery bundles aside before restaging.
The managed filesystem adapter owns transport cleanup and entry quarantine; the CLI owns process retirement and maps the finite preparation result to `FORCE_RESET` evidence. Unknown upstream contents fail closed. Force
replaces command collisions and starts with fresh workspace enrollment; source trees
are untouched. A force dry run verifies and reports without performing reset effects.
Force plugin activation moves the exact same-user Kast plugin entry into private recovery storage without following a symlink target. The private CLI, App Server, and MCP launchers are required; installation cannot publish a CLI-only payload.

The public installer registers `kast-mcp-complete` once in user-level Codex MCP
configuration. It checks for a foreign `kast` entry before replacing the selected
installation and removes only its own entry on uninstall. Terminal Codex then
discovers the exact Gradle root for each session. Modern discovery or legacy
initialization queues native preparation for that root using the selected IDEA
lifecycle. Later semantic demand joins the same preparation and waits for exact
readiness. The initialize response explains that wait, automatic linked-model
reload, exact-reference reuse, relation coverage, continuations, and stage-specific
recovery. The terminal MCP catalog does not expose manual refresh or
`workspace_lifecycle`.
Its `add_declaration` tool plans, signs the exact native challenge, applies, and verifies
within one call, attempting recovery if application is unverified. The App Server exposes the same public tool and signs its exact
plan internally. If apply is cancelled, only a complete native `prior_state` or
`rolled_back` recovery settles the invocation as known and releases the App
Server workspace lane. Incomplete recovery leaves the lane protected.
The app-server suite is always installed. Activation may still be pending with
a finite reason when the host cannot start the service.

The app server exposes every qualified tool in the canonical agent catalog. There is no subset-selection setting or disabled product variant. Persistent installation activates the login service; private development sessions defer activation. Retired overrides reject before installation effects.

Read [configuration](../contracts/configuration.md) for ingress and ownership rules.

`ConfigurationSchemaDocument` defines the shared document. The CLI-owned `InstalledConfigurationSchema` is the sole catalogue generator and includes operational limits from protocol, broker, installation, and CLI owners.

`assembleRelease` ships exactly the control tarball and matching hosted-plugin ZIP with checksums. `GenerateControlMetadataTask` derives `ide-host.json` from the actual plugin bytes and build identities; installation verifies the exact name, version, length and digest. No semantic-runtime manifest, isolated indexer payload or topology store is shipped. Installation manifests use schema 2 and `hostedPluginSha256`; lifecycle inspection still admits historical schema-1 records so old owned installations can be retired safely.

Release asset construction, checksums, SBOM inventory, and publication agree on the control and plugin pair.

The [installed knowledge contract](../contracts/installed-knowledge.md) describes
`kast knowledge`, its isolated PSI extraction, verified module ownership and
scoped guide resources staged with the control product.

The generated catalogue also declares the four execution ceilings, host backlog
and connection capacity, and native source continuation byte/TTL limits.
`:cli:generateConfigurationCatalogue` owns the snapshot;
`verifyConfigurationIngress` checks its exact agreement with the Kotlin owners.
The [configuration contract](../contracts/configuration.md) records the defaults.

The routine assembled-product check installs the control archive and matching
hosted plugin in an owned temporary root. It verifies archive layout, version
and plugin identity, required launchers, and one fail-closed public command.
Provider catalog policy stays in its Kotlin owner tests. Semantic query results, IDEA behavior, and Codex
attachment are outside this packaging boundary; their production rules have
owner-local Kotlin tests. The retired Python native acceptance matrix supplies
no current runtime qualification.

Workspace registry retention emits a bounded `kast_installation_registry`
observation with its exact outcome. A corrupt prior registry fails prior lifecycle
admission and keeps the selected installation and declared activation anchors. Failure to stop an exact prior service,
filesystem failure, and interruption remain explicit failures, rather than being
reported as successful retirement.

After the selected upgrade receipt is sealed, historical cleanup admits each
prior version through the lifecycle boundary. A separate review enumerates
prior Kast processes, login items, recovery bundles, plugin backups, external
anchors, and version entries that no longer pass admission. It preserves the selected
version and the plugin backup named by its recovery receipt. A terminal user
must answer `yes` for each uncertain entry; a declined or unattended entry is
retained with a finite reason. Process command lines supply runtime evidence
for version-root tracing, and an exact Kast launchd label is booted out before
its reviewed login item is removed. A process or entry that changes during
review is retained.

Selected IDEA discovery retains one canonical home across upgrades. Typed metadata resolution identifies its macOS ARM bundle and executable, rejects ambiguity and escapes, and admits the 262 release line rather than an exact patch. Installation inspection includes the launch observation and persists the derived receipt. A legacy selection can still attach to its live lifecycle endpoint when cold-launch metadata is unavailable.

Control assembly packages `share/kast/provider-catalog.json` from the same hosted
schema projection as the CLI. The artifact contains hosted metadata and schemas,
with no executable grammar. Distribution layout requires it; runtime release
admission qualifies the actual staged file through the production provider.

After installation admission and before retiring or replacing any installation, the installer enrolls or preserves the user-owned broker key pair. Partial, mismatched, unsafe or busy enrollment rejects with the finite trust failure. Plan mode creates no keys. Bounded trust observations contain only completion status or rejection cause, never key material. Keys live outside versioned payloads and remain unchanged across reinstalls and upgrades.

The control payload includes private executables at `share/kast/libexec/kast-daemon` and `share/kast/libexec/kast-service`, inside the existing inventory and checksum boundary. Launchd invokes the daemon directly; the installer invokes service control without the public CLI graph. The login LaunchAgent retains the service label and daemon executable, adding only the private login argument. Offline recovery admits that entry only when removing the argument from its bounded bytes yields the retained service plist for the exact Codex-home profile; it also recognizes the older one-shot entry. The assembled-product check verifies the private launchers are present and executable; their argument and readiness rules remain with their Kotlin owners.

`kast connect <codex|copilot|pi> --force` authorizes takeover of that harness's
Kast registration slot. The existing lock and release-manifest admission still
apply. Registration and receipt preimages are captured before replacement;
failed transactions restore both. Codex restoration uses the full original
configuration bytes, preserving fields outside its inspection projection.
Failed restoration retains its backup and reports the recovery destination.
Symlinked registration paths reject before mutation. Connecting records
configuration and does not establish that a client loaded or invoked the tools.
