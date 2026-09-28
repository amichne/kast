# Packaging Python removal ledger

This ledger records the test boundary before removing the Python acceptance
harness. Kotlin entries below name a module and test class under its test source
set. `Overlap` means a named Kotlin test exercises the production rule; it does not claim that
the Python fixture and Kotlin fixture are identical. A harness-only assertion
does not require a replacement test when its harness is removed. Keep an
installed-artifact assertion only if it proves a fact unavailable to source or
module tests.

At `fedda90ac`, `packaging/` contained 36 `test-*.py` entry points and 370 named
test methods. The portable runner executes 35 entry points, excluding only
`test-installed-codex-host.py`. The former `load_tests` hook in
`test-hosted-read-regression.py` additionally reran 16 of those files (122
methods). The first cleanup commit removed that aggregation; each file remains
individually discoverable by the portable runner until its owning boundary is
retired. The next cleanup removed the Python native and released acceptance
entry points and their helper graph. The suite names below remain as a record
of the deletion decision; they are no longer runnable paths.

## Semantic and hosted read cases: remove with the Python native harness

| Python suite in `packaging/` | Existing Kotlin production coverage | Residual Python assertion |
| --- | --- | --- |
| `test-hosted-read-regression.py` | `app-server/query/PublicToolContractTest`, `PublicToolSchemaTest`; `protocol/wire/CanonicalRequestDtoSerializationTest`; `query/service/QueryWalkCompositionTest`; `symbol/intellij/IntellijDiscoveryKindAdmissionTest` | Python request DTOs, fixture preservation, replay oracle, and report bounds. Its schema-example assertion duplicates `PublicToolContractTest.every authored example...`. |
| `test-hosted-authority-read.py` | `source/service/SourceReadServiceTest` checks movement before and during publication; `query/protocol/ReacquiringQueryReferencesTest` and `workspace/intellij-read/ProjectReadEpochVfsOverflowTest` own stale authority. | Native private edit and exact fixture restoration. |
| `test-hosted-budget-read-regression.py` | `workspace/intellij-read/hosted/HostedBudgetDimensionTest`; `query/protocol/QueryCheckpointReplayTest`; `runtime/hosted/HostedReadAllowanceIdentityTest` | Python request and receipt comparisons. |
| `test-hosted-compact-source-regression.py` | `protocol/wire/CompactSourceDocumentTest`; `runtime/hosted/HostedSourceResponseTest`; `cli/SourceBudgetSchemaTest` | One Python compact request shape. |
| `test-hosted-diagnostic-pages-regression.py` | `diagnostic/service/DiagnosticScanServiceTest`; `query/protocol/DiagnosticContinuationProtocolTest`; `diagnostic/intellij/DiagnosticEnumerationTest` | Native warning-occurrence oracle and Python drain receipt. |
| `test-hosted-kotlin-call-regression.py` | `relation/intellij/KotlinCallOwnershipTest`; `source/intellij/IntellijSourceCallReferenceReadTest`; `query/service/QueryOccurrenceCompositionTest` | Cross-tool Python oracle and native call fixture. |
| `test-hosted-repair-budget-regression.py` | `workspace/intellij-read/hosted/HostedExecutionBudgetTest`; `runtime/hosted/HostedRejectedBudgetTest` | Python matrix receipt for actual installed grants. |
| `test-hosted-repair-time-observation.py` | `workspace/intellij-read/hosted/HostedDeadlineEvidenceTest`, `HostedContainmentReportTest`, `HostedPublicationDeadlineTest` | Python native log timing projection. |
| `test-hosted-resume-budget-regression.py` | `query/protocol/QueryCheckpointReplayTest`, `SourceProgressProjectionTest`; `source/intellij/SourceContinuationRetentionTest` | Python same-request increase-grant oracle. |
| `test-hosted-source-failure-regression.py` | `cli/SourceFailureMatrixTest`, `ReadRejectionSchemaParityTest`; `runtime/hosted/HostedRejectedBudgetTest` | Python CLI stderr and provider-envelope classification. |
| `test-hosted-vfs-overflow-regression.py` | `workspace/intellij-read/ProjectReadEpochVfsOverflowTest`; `runtime/hosted/HostedVfsRefreshTest` | Native log-window and restored-source receipt. |
| `test-hosted-workspace-refresh.py` | `runtime/hosted/workspace/WorkspaceRefreshServiceTest`, `WorkspaceRefreshVfsOrderTest`; `app-server/ide/ExistingIdeModelRefreshTest` | Native Gradle reload and fixture restoration. |
| `test-hosted-wire-schema.py` | `protocol/wire/CanonicalOperationWireBindingsTest`; `runtime/hosted/HostedWorkspaceRefreshSchemaTest`; `workspace/intellij-read/hosted/HostedFailureEncodingFixtureTest` | Exact staged-jar schema digest and Python validator. |
| `test-hosted-peer-probe.py` | `runtime/hosted/HostedEndpointTest` | Python socket/log observer, including one macOS kqueue case. The Kotlin native transport test belonged to the retired harness and was removed too. |
| `test-hosted-runtime-observation.py` | `workspace/intellij-read/hosted/HostedReadDiagnosticsTest` | Python process/log observer. The Kotlin native observation fixture was removed too. |
| `test-native-provider-qualification.py` | No production Kotlin rule; the Kotlin qualification tests exercised the retired controller protocol and were removed with it. | Python report admission before the native read matrix. |
| `test-native-fixture-probe.py` | No production Kotlin rule; probe protocol tests exercised only the retired fixture plugin and were removed with it. | Python probe client framing. |
| `test-hosted-acceptance-fixture.py` | `distribution/managed/SelectedIdeInstallationTest`; `workspace/intellij-read/ExistingProjectAdmissionTest` | Python disposable IDEA fixture preparation. |
| `test-hosted-change-acceptance.py` | `change/plan`, `change/apply`, `change/verify` tests | Python matrix/report qualification, artifact hashes, and cleanup assertions. |

These Kotlin tests prove their own named rules. The one distinct claim made by
the Python native runner is that a particular staged product worked with a
particular live IDEA. That is an explicit runtime qualification, not packaging
unit coverage. Deleting the runner also deletes its report and fixture tests;
it does not assert that Kotlin owner tests prove native composition. The
orphaned Kotlin controller and fixture plugin, including tests of their own
protocol, were removed after their sole Python caller disappeared.

Main subsequently added a `replace_body` case to that same native controller.
`app-server/query/PublicToolContractTest`, `change/contract/LiveReplaceBodyPlanTest`,
`change/verify/LiveReplaceBodyVerificationTest`, and
`change/verify/LiveReplaceBodyReceiptCodecTest` cover its contract, plan,
postimage, and receipt rules. The deleted case uniquely exercised a live IDE
replacement followed by fresh-reference reuse. This removal retires that native
composition claim; these owner tests do not establish it. The controller's new
private failure-document test described only its own retired diagnostic format.

## Release and Codex acceptance cases: remove with the released harness

| Python suite in `packaging/` | Existing Kotlin production coverage | Residual Python assertion |
| --- | --- | --- |
| `test-released-acceptance-product.py` | `cli/installation/ControlDistributionLayoutTest`; `distribution/managed/ControlPayloadInventoryTest` | Original tagged assets, checksum-bound install, and installed wrapper through the Python release fixture. |
| `test-released-upgrade-acceptance.py` | `cli/installation/InstallationWorkflowTest`, `InstallationUpgradeSafetyTest`; `app-server/InstalledDaemonUpgradeTest` | Two original-release shell sessions and adjacent tagged upgrade composition. |
| `test-released-coordinator-acceptance.py` | `app-server/InstalledCoordinatorTest`, `DesktopAttachmentTest`, `host/DesktopStdioProtocolIntegrationTest` | Real installed Codex process attachment through the Python fixture. |
| `test-released-tool-inventory.py` | `cli/PackagedProviderCatalogTest`, `PreferredReadProjectionTest`; `app-server/provider/KastCatalogAdmissionTest`; `protocol/registry/CanonicalAgentToolDefinitionsTest` | A second Python table of the five tool names and operations. This is direct duplicate catalog policy. |
| `test-installed-codex-host.py` | `app-server/InstalledCoordinatorTest`, `host/DesktopStdioProtocolIntegrationTest` | Opt-in assembled artifact and real Codex handshake; no routine Kotlin test claims that live result. |
| `test-installed-codex-lifecycle.py` | `cli/installation/InstallationUpgradeSafetyTest` covers private-service selection in production. | Python native harness choosing legacy or private control. |

If released native qualification remains a product requirement, give it one
explicit owner outside routine packaging tests. Do not keep the Python fixture
matrix simply to test its own receipt format. The routine assembled-product
smoke below retains archive and installer identity without a semantic query.

## Installer and artifact cases: retain or shrink at their actual boundary

| Python suite in `packaging/` | Existing Kotlin production coverage | Still distinct while current entry point exists |
| --- | --- | --- |
| `test-acceptance-environment.py` | No production Kotlin counterpart needed. | Removed the native fixture machinery; `test-installer-fixture.py` checks only owned-root and child-environment boundaries. |
| `test-acceptance-idea.py` | `distribution/managed/SelectedIdeInstallationTest` covers IDE selection. | Fixture-specific exact distribution admission; remove if no native Python fixture remains. |
| `test-install-checkout.py` | `cli/installation/InstallationRequestTest`, `ControlDistributionLayoutTest`. | Real checkout shell invocation, argument delivery, and host platform rejection. |
| `test-install-local.py` | `cli/installation/InstallationWorkflowTest` covers install behavior. | Thin shell adapter forwarding the staged product. |
| `test-installation-lifecycle.py` | `cli/installation/InstallationWorkflowTest`, `InstallationUpgradeSafetyTest`, `PriorInstallationReplacementTest` cover the normal Kotlin installer. | Selected-installation offline removal and Python retirement adapter used by `install.sh`. |
| `test-installation-recovery.py` | `cli/installation/InstallationActivationTest`, `InstallationUpgradeSafetyTest` cover normal installation outcomes. | Python plugin activation, durable backup/recovery, and offline damaged-payload behavior. |
| `test-installer-entrypoint.py` | `cli/installation/InstallationRequestTest`, `InstallationWorkflowTest` cover parsed installer requests and effects. | Public `install.sh` flags, download handoff, post-install ordering, and uninstall dispatch. |
| `test-installer-removal.py` | Kotlin tests cover managed retirement, not public shell dispatch. | Selected offline lifecycle call and manifestless refusal. |
| `test-prune-prior-installations.py` | `cli/installation/PriorInstallationReplacementTest` covers admitted prior replacement. | Post-upgrade review of uncertain historical entries, exact `yes`, selected backup protection, launchd/process identity. |

The shipped Python programs are `installation-lifecycle.py`,
`installation-recovery.py`, `prune-prior-installations.py`, and
`codex-mcp-registration.py`; `build.gradle.kts` copies all four into the control
product, and `install.sh` calls them. They are a separate production migration.
The only reason to keep their Python-specific tests is that the shipped Python
behavior still exists. Never delete uncertain-entry protection merely because
the normal Kotlin upgrade has a test.

## Build tooling and assembled artifact cases

| Python suite in `packaging/` | Existing Kotlin coverage | Decision |
| --- | --- | --- |
| `test-public-query-generation.py` | `app-server/query/PublicToolContractTest`, `PublicToolSchemaTest` verify generated contract behavior. | Keep only while `generate-public-query.py` is the build generator; it tests invalid generator inputs. |
| `test-configuration-ingress.py` | `distribution/contract/configuration` tests prove configuration meaning. | Keep only while `configuration_ingress.py` is the static ingress guard; it tests scanner/snapshot behavior. |

`run-installed-product.py` is the current assembled-product boundary. It keeps one real install from the assembled archive,
exact artifact identity, required files and permissions including the native
`kast-management` executable, one version-owned
launcher result, and fail-closed unsupported ingress. Semantic tool-name
assertions are now owned by the Kotlin catalog tests. No packaging check needs
to execute `query_symbols` or `check_diagnostics`. The management command's
behavior belongs to `distribution/cli/ManagementCliTest`; the assembled install
also exercises its public installer publication path.

## Removal rule

For each deletion, identify the exact production fact and its owning Kotlin
test. If only Python fixture serialization, fake transport, or report formatting
remains, delete that assertion with its harness. If the assertion observes an
assembled installer or a native process, keep at most one explicitly named
composition check for that boundary. Do not manufacture a replacement test to
preserve a test count. After each removal, update Gradle, CI, docs, and the
source-bound knowledge page so no retired entry point remains advertised.
