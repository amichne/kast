package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitFailure
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimitValueFailure
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.BoundedModuleName
import io.github.amichne.kast.workspace.intellij.read.BoundedSourceRootIdentity
import io.github.amichne.kast.workspace.intellij.read.CodeSourceRootClassification
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectObservationStage
import io.github.amichne.kast.workspace.intellij.read.GradleModuleOwnershipFailure
import io.github.amichne.kast.workspace.intellij.read.IdeRootMappingFailure
import io.github.amichne.kast.workspace.intellij.read.IdeSourceRootEvidence
import io.github.amichne.kast.workspace.intellij.read.NamedGradleCaptureStage
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Source-bound bytes bridge real host encoders to CLI schemas without an IDE dependency in the CLI. */
class HostedFailureEncodingFixtureTest {
    @Test
    fun `every hosted read failure encoding matches the independently schema checked fixture`() {
        val report =
            ExecutionBudgetReport.from(
                (HostedSemanticTimeAllowance.admit(ReadLimits.Default, 100L) as Refinement.Refined)
                    .value
                    .executionBudget
            )
        val documents =
            failures().flatMap { failure ->
                val bare = HostedQueryWire.encode(HostedQueryResult.Rejected(failure, HostedQueryStage.SEMANTIC_READ))
                // These are actual containment families after semantic admission; no grant is invented for other
                // families.
                val admitted =
                    failure is HostedQueryFailure.Freshness ||
                        failure is HostedQueryFailure.Platform ||
                        failure in
                            listOf(
                                HostedQueryFailure.RETIRED,
                                HostedQueryFailure.STALE_REQUEST,
                                HostedQueryFailure.READ_PREEMPTED,
                                HostedQueryFailure.CANCELLED,
                                HostedQueryFailure.BUDGET_EXCEEDED,
                            )
                if (admitted)
                    listOf(
                        bare,
                        HostedQueryWire.encode(
                            HostedQueryResult.Rejected(
                                failure,
                                HostedQueryStage.SEMANTIC_READ,
                                ExecutionBudgetPresence.Present(report),
                            )
                        ),
                    )
                else listOf(bare)
            }
        val actual = Json.encodeToString(FailureDocuments(documents))
        val reportPath = Path.of("build/reports/hosted-read-failure-encodings.json")
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, actual)
        assertEquals(
            Files.readString(Path.of("../../cli/src/test/resources/hosted-read-failure-encodings.json")).trim(),
            actual,
        )
    }

    private fun freshnessFailures(): List<VfsPassiveReadAdmissionFailure> =
        variants(VfsPassiveReadAdmissionFailure::class) { type ->
            when (type) {
                VfsPassiveReadAdmissionFailure.Unavailable::class ->
                    variants(VfsPassiveReadUnavailableCause::class) { child ->
                            when (child) {
                                VfsPassiveReadUnavailableCause.ObservationFailed::class ->
                                    ProjectReadEpochObservationStage.entries.map(
                                        VfsPassiveReadUnavailableCause::ObservationFailed
                                    )
                                else -> error("Uncovered unavailable variant: $child")
                            }
                        }
                        .map(VfsPassiveReadAdmissionFailure::Unavailable)
                else -> error("Uncovered freshness variant: $type")
            }
        }

    private fun namedScopeFailures(): List<NamedGradleSourceScopeFailure> =
        variants(NamedGradleSourceScopeFailure::class) { type ->
            val evidence =
                IdeSourceRootEvidence(
                    BoundedModuleName.observe("fixture"),
                    BoundedSourceRootIdentity.observe("/fixture/src"),
                )
            when (type) {
                NamedGradleSourceScopeFailure.RootMapping::class -> rootMappingFailures(evidence)
                NamedGradleSourceScopeFailure.ModuleOwnership::class ->
                    GradleModuleOwnershipFailure.entries.map {
                        NamedGradleSourceScopeFailure.ModuleOwnership(evidence.module, it)
                    }
                NamedGradleSourceScopeFailure.ObservationFailed::class ->
                    NamedGradleCaptureStage.entries.map(NamedGradleSourceScopeFailure::ObservationFailed)
                NamedGradleSourceScopeFailure.ModelRejected::class ->
                    listOf(
                        NamedGradleSourceScopeFailure.ModelRejected(
                            WorkspaceSearchScopeModel.compile(
                                (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")) as Refinement.Refined)
                                    .value,
                                ImportedWorkspaceModelState.INCOMPLETE,
                                emptyList(),
                            ) as WorkspaceSearchScopeModelCompilation.Rejected
                        )
                    )
                else -> error("Uncovered named scope variant: $type")
            }
        }

    private fun <T : Any> variants(type: KClass<T>, constructed: (KClass<out T>) -> List<T>): List<T> =
        type.sealedSubclasses
            .sortedBy { it.simpleName }
            .flatMap { child -> child.objectInstance?.let(::listOf) ?: constructed(child) }

    @Serializable private data class FailureDocuments(val documents: List<String>)

    private fun configurationFailures(): List<HostedQueryFailure> =
        listOf(
                ReadLimitFailure.UnknownParameter,
                ReadLimitFailure.InconsistentBounds(
                    ReadLimitParameter.HOST_CONNECTION_MILLIS,
                    ReadLimitParameter.CLIENT_EXCHANGE_MILLIS,
                ),
            )
            .plus(
                ReadLimitValueFailure.entries.map {
                    ReadLimitFailure.InvalidValue(ReadLimitParameter.HOST_CONNECTION_MILLIS, it)
                }
            )
            .map(HostedQueryFailure::Configuration)

    private fun projectFailures(): List<HostedQueryFailure> =
        variants(ExistingProjectAdmissionFailure::class) { child ->
                when (child) {
                    ExistingProjectAdmissionFailure.HostIncompatible::class ->
                        IdeHostCompatibilityField.entries.map { field ->
                            ExistingProjectAdmissionFailure.HostIncompatible(
                                IdeHostCompatibilityFailure.Malformed(
                                    field,
                                    IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT,
                                )
                            )
                        }
                    ExistingProjectAdmissionFailure.ObservationFailed::class ->
                        ExistingProjectObservationStage.entries.map(ExistingProjectAdmissionFailure::ObservationFailed)
                    else -> error("Uncovered project admission variant: $child")
                }
            }
            .map(HostedQueryFailure::ProjectAdmission)

    private fun epochFailures(): List<HostedQueryFailure> =
        variants(ProjectReadEpochObservationFailure::class) { child ->
                when (child) {
                    ProjectReadEpochObservationFailure.ObservationFailed::class ->
                        ProjectReadEpochObservationStage.entries.map(
                            ProjectReadEpochObservationFailure::ObservationFailed
                        )
                    else -> error("Uncovered read epoch variant: $child")
                }
            }
            .map(HostedQueryFailure::ReadEpoch)

    private fun failures(): List<HostedQueryFailure> =
        variants(HostedQueryFailure::class) { type ->
            when (type) {
                HostedQueryFailure.Configuration::class -> configurationFailures()
                HostedQueryFailure.Platform::class ->
                    HostedPlatformFailureCause.entries.map(HostedQueryFailure::Platform)
                HostedQueryFailure.ProjectAdmission::class -> projectFailures()
                HostedQueryFailure.ModelCapture::class ->
                    DetachedModelCaptureFailure.entries.map {
                        HostedQueryFailure.ModelCapture(DetachedModelCapture.Rejected(it))
                    }
                HostedQueryFailure.ReadEpoch::class -> epochFailures()
                HostedQueryFailure.Freshness::class -> freshnessFailures().map(HostedQueryFailure::Freshness)
                HostedQueryFailure.LiveAuthority::class ->
                    LiveSemanticReadFailure.entries.map(HostedQueryFailure::LiveAuthority)
                HostedQueryFailure.NamedSourceScope::class ->
                    namedScopeFailures().map(HostedQueryFailure::NamedSourceScope)
                else -> error("Uncovered hosted failure variant: $type")
            }
        }

    private fun rootMappingFailures(evidence: IdeSourceRootEvidence): List<NamedGradleSourceScopeFailure> =
        listOf(
                IdeRootMappingFailure.SourceFolderUnavailable(evidence),
                IdeRootMappingFailure.SourceFolderObservationFailed(evidence),
                IdeRootMappingFailure.GradleOwnerMissing(evidence),
                IdeRootMappingFailure.UnsupportedRootType(evidence),
                IdeRootMappingFailure.SourcePropertiesUnavailable(evidence),
                IdeRootMappingFailure.RootClassificationMismatch(
                    evidence,
                    CodeSourceRootClassification(
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    ),
                    CodeSourceRootClassification(
                        WorkspaceSourceRootKind.TEST,
                        WorkspaceSourceRootProvenance.GENERATED,
                    ),
                ),
            )
            .also { values ->
                assertEquals(
                    IdeRootMappingFailure::class.sealedSubclasses.toSet(),
                    values.map { it::class }.toSet(),
                )
            }
            .map(NamedGradleSourceScopeFailure::RootMapping)
}
