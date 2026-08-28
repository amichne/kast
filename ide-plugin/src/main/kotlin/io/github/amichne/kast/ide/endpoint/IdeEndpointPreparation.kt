package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.project.Project
import io.github.amichne.kast.ide.compatibility.AdmittedIdeHostCompatibilityMetadata
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorCandidate
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointFraming
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointHostKind
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointPathFailure
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSchema
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeProcessId
import io.github.amichne.kast.protocol.wire.metadata.IdeRuntimeEpoch
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectAdmission
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProjectAdmissionFailure
import io.github.amichne.kast.runtime.ide.read.composition.HostedIdeReadProductionComposition
import io.github.amichne.kast.runtime.ide.read.composition.HostedIdeReadProductionCompositionFailure
import io.github.amichne.kast.runtime.ide.read.composition.HostedIdeReadProductionCompositionPreparation
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparation
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparationFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import java.nio.file.Path

data class IdeEndpointPreparationCandidate(
    val descriptorRoot: IdeEndpointCanonicalRoot,
    val runtime: HostedIdeReadRuntimePreparation,
    val compatibilityPolicy: IdeHostCompatibilityPolicy,
    val socketDirectory: IdeEndpointSocketDirectory,
    val processId: IdeProcessId,
    val runtimeEpoch: IdeRuntimeEpoch,
)

sealed interface IdeEndpointPreparation {
    data class Prepared(
        val endpoint: PreparedIdeEndpoint,
    ) : IdeEndpointPreparation

    data class Rejected(
        val failure: IdeEndpointPublicationFailure,
    ) : IdeEndpointPreparation

    companion object {
        /**
         * Proof transition: `IdeEndpointPreparationCandidate -> IdeEndpointPreparation`.
         *
         * Establishes exact equality with the admitted Project root, complete four-operation
         * runtime construction, deterministic UDS location, and a canonically admitted descriptor
         * v2 before returning [PreparedIdeEndpoint]. Expected wrong-root or partial-runtime state
         * is finite [IdeEndpointPublicationFailure]. Raw descriptor fields are extracted only here,
         * at the hosted endpoint publication boundary.
         */
        fun prepare(candidate: IdeEndpointPreparationCandidate): IdeEndpointPreparation {
            val runtime = when (val prepared = candidate.runtime) {
                is HostedIdeReadRuntimePreparation.Prepared -> prepared.runtime
                is HostedIdeReadRuntimePreparation.Rejected -> return Rejected(
                    IdeEndpointPublicationFailure.PARTIAL_RUNTIME,
                )
            }
            if (runtime.canonicalRoot != candidate.descriptorRoot) {
                return Rejected(IdeEndpointPublicationFailure.WRONG_ROOT)
            }
            val location = when (
                val located = IdeEndpointLocation.locate(
                    candidate.socketDirectory,
                    candidate.descriptorRoot,
                )
            ) {
                is Refinement.Refined -> located.value
                is Refinement.Rejected -> return Rejected(
                    IdeEndpointPublicationFailure.SOCKET_BIND_FAILED,
                )
            }
            val compatibility = runtime.compatibility
            val descriptor = when (val admission = IdeEndpointDescriptorV2.create(
                    IdeEndpointDescriptorCandidate(
                        schema = IdeEndpointSchema.V2.identity,
                        canonicalRoot = candidate.descriptorRoot.value,
                        hostKind = IdeEndpointHostKind.IDE_PROJECT.identity,
                        processId = candidate.processId.value,
                        ideBuild = compatibility.ideBuild.value,
                        kotlinPluginBuild = compatibility.kotlinPluginBuild.value,
                        kastPluginVersion = compatibility.kastPluginVersion.value,
                        runtimeProtocolIdentity = compatibility.runtimeProtocolIdentity.value,
                        operationRegistryDigest = compatibility.operationRegistryDigest.value,
                        wireSchemaDigest = compatibility.wireSchemaDigest.value,
                        socketPath = location.socketPath.value,
                        framing = IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1.identity,
                        runtimeEpoch = candidate.runtimeEpoch.value,
                        capabilities = compatibility.capabilities.capabilities.map {
                            it.operation.id.value
                        },
                    ),
                    candidate.compatibilityPolicy,
                )) {
                is IdeEndpointDescriptorAdmission.Admitted -> admission.descriptor
                is IdeEndpointDescriptorAdmission.Rejected -> return Rejected(
                    IdeEndpointPublicationFailure.DESCRIPTOR_PUBLICATION_FAILED,
                )
            }
            return Prepared(
                PreparedIdeEndpoint(
                    runtime.canonicalRoot,
                    runtime,
                    descriptor,
                    location,
                    candidate.compatibilityPolicy,
                ),
            )
        }
    }
}

internal enum class IdeEndpointDeferredReadiness {
    PROJECT_NOT_INITIALIZED,
    GRADLE_MODEL_UNAVAILABLE,
    GRADLE_MODEL_INCOMPLETE,
    DUMB_MODE,
}

internal sealed interface IdeEndpointStartupFailure {
    data object ProjectRootUnavailable : IdeEndpointStartupFailure
    data object HostConfigurationUnavailable : IdeEndpointStartupFailure
    data class ProjectRejected(
        val cause: HostedIdeReadProjectAdmissionFailure,
    ) : IdeEndpointStartupFailure
    data class RuntimeRejected(
        val cause: HostedIdeReadRuntimePreparationFailure,
    ) : IdeEndpointStartupFailure
    data class ProjectModelRejected(
        val causes: Set<DetachedModelCaptureFailure>,
    ) : IdeEndpointStartupFailure
    data class CompositionRejected(
        val cause: HostedIdeReadProductionCompositionFailure,
    ) : IdeEndpointStartupFailure
    data object EndpointGenerationExhausted : IdeEndpointStartupFailure
    data class EndpointRejected(
        val cause: IdeEndpointPublicationFailure,
    ) : IdeEndpointStartupFailure
}

internal sealed interface IdeEndpointStartup {
    data class Prepared(
        val endpoint: PreparedIdeEndpoint,
    ) : IdeEndpointStartup

    data class Deferred(
        val readiness: IdeEndpointDeferredReadiness,
    ) : IdeEndpointStartup

    data class Rejected(
        val failure: IdeEndpointStartupFailure,
    ) : IdeEndpointStartup
}

internal object IdeEndpointModelCaptureAdmission {
    /**
     * Proof transition: `Set<DetachedModelCaptureFailure> -> IdeEndpointStartup`.
     *
     * Establishes that the exact transient clean-import ownership observation remains closed as
     * [IdeEndpointStartup.Deferred] while every mixed or terminal capture failure remains a
     * complete [IdeEndpointStartup.Rejected]. Raw module state remains confined to the IntelliJ
     * capture adapter; this boundary consumes only its finite failure evidence.
     */
    fun admit(failures: Set<DetachedModelCaptureFailure>): IdeEndpointStartup {
        if (failures == setOf(DetachedModelCaptureFailure.NOT_GRADLE_OWNED)) {
            return IdeEndpointStartup.Deferred(
                IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
            )
        }
        return when {
            DetachedModelCaptureFailure.PROJECT_NOT_INITIALIZED in failures ->
                IdeEndpointStartup.Deferred(
                    IdeEndpointDeferredReadiness.PROJECT_NOT_INITIALIZED,
                )
            DetachedModelCaptureFailure.GRADLE_MODEL_INCOMPLETE in failures ->
                IdeEndpointStartup.Deferred(
                    IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
                )
            DetachedModelCaptureFailure.PROJECT_DUMB in failures ||
                DetachedModelCaptureFailure.READ_PREEMPTED in failures ->
                IdeEndpointStartup.Deferred(IdeEndpointDeferredReadiness.DUMB_MODE)
            else -> IdeEndpointStartup.Rejected(
                IdeEndpointStartupFailure.ProjectModelRejected(failures),
            )
        }
    }
}

/** Live project-service startup boundary with no import, refresh, traversal, or fallback path. */
internal object LiveIdeEndpointStartup {
    /**
     * Proof transition: `(Project, ProjectEndpointGenerationSource) -> IdeEndpointStartup`.
     *
     * Refines the existing Project through the compiled KVP-012 tuple and [HostedIdeReadProject],
     * preserves transient cached-readiness failures as [IdeEndpointStartup.Deferred], and consumes
     * a generation only after complete four-operation runtime construction. All other expected
     * failures remain finite [IdeEndpointStartupFailure]. Raw Project metadata, PID, and endpoint
     * directory values leave only at this outer hosted service boundary.
     */
    suspend fun prepare(
        project: Project,
        generations: ProjectEndpointGenerationSource,
    ): IdeEndpointStartup {
        val canonicalRoot = when (
            val parsed = project.basePath?.let(IdeEndpointCanonicalRoot::parse)
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected, null -> return rejected(
                IdeEndpointStartupFailure.ProjectRootUnavailable,
            )
        }
        val metadata = when (val admitted =
            AdmittedIdeHostCompatibilityMetadata.admitGenerated()
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val workspaceRoot = when (val parsed = CanonicalWorkspaceRoot.fromCanonicalPath(
            Path.of(canonicalRoot.value),
        )) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.ProjectRootUnavailable,
            )
        }
        val admittedProject = when (val admission = AdmittedIdeProject.admit(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
        )) {
            is ExistingProjectAdmission.Admitted -> admission.project
            is ExistingProjectAdmission.Rejected -> return classify(
                HostedIdeReadProjectAdmissionFailure.ProjectRejected(admission.failure),
            )
        }
        val hostedProject = when (val admission = HostedIdeReadProject.retain(
            admittedProject,
            canonicalRoot,
        )) {
            is HostedIdeReadProjectAdmission.Admitted -> admission.project
            is HostedIdeReadProjectAdmission.Rejected -> return classify(admission.failure)
        }
        val model = when (val capture = admittedProject.captureDetachedModelAsync()) {
            is DetachedModelCapture.Captured -> capture.model
            is DetachedModelCapture.Rejected -> return IdeEndpointModelCaptureAdmission.admit(
                capture.failures,
            )
        }
        val composition = when (val preparation = HostedIdeReadProductionComposition.prepare(
            hostedProject,
            model,
        )) {
            is HostedIdeReadProductionCompositionPreparation.Prepared -> preparation.composition
            is HostedIdeReadProductionCompositionPreparation.Rejected -> return rejected(
                IdeEndpointStartupFailure.CompositionRejected(preparation.failure),
            )
        }
        val socketDirectory = when (val parsed = IdeEndpointSocketDirectory.parse("/tmp")) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val processId = when (val parsed = IdeProcessId.parse(ProcessHandle.current().pid())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val runtimeEpoch = when (val issued = generations.issue()) {
            is ProjectEndpointGenerationIssuance.Issued -> issued.epoch
            ProjectEndpointGenerationIssuance.Exhausted -> return rejected(
                IdeEndpointStartupFailure.EndpointGenerationExhausted,
            )
        }
        val evidenceGeneration = when (
            val parsed = EvidenceGeneration.parse(runtimeEpoch.value)
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val runtime = when (val preparation = composition.activate(evidenceGeneration)) {
            is HostedIdeReadRuntimePreparation.Prepared -> preparation
            is HostedIdeReadRuntimePreparation.Rejected -> return rejected(
                IdeEndpointStartupFailure.RuntimeRejected(preparation.failure),
            )
        }
        return when (val prepared = IdeEndpointPreparation.prepare(
            IdeEndpointPreparationCandidate(
                descriptorRoot = canonicalRoot,
                runtime = runtime,
                compatibilityPolicy = metadata.compatibilityPolicy,
                socketDirectory = socketDirectory,
                processId = processId,
                runtimeEpoch = runtimeEpoch,
            ),
        )) {
            is IdeEndpointPreparation.Prepared -> IdeEndpointStartup.Prepared(prepared.endpoint)
            is IdeEndpointPreparation.Rejected -> rejected(
                IdeEndpointStartupFailure.EndpointRejected(prepared.failure),
            )
        }
    }

    private fun classify(failure: HostedIdeReadProjectAdmissionFailure): IdeEndpointStartup =
        when (failure) {
            HostedIdeReadProjectAdmissionFailure.InvalidEndpointRoot -> rejected(
                IdeEndpointStartupFailure.ProjectRejected(failure),
            )
            is HostedIdeReadProjectAdmissionFailure.ProjectRejected -> when (failure.cause) {
                ExistingProjectAdmissionFailure.ProjectNotInitialized -> deferred(
                    IdeEndpointDeferredReadiness.PROJECT_NOT_INITIALIZED,
                )
                ExistingProjectAdmissionFailure.GradleModelUnavailable -> deferred(
                    IdeEndpointDeferredReadiness.GRADLE_MODEL_UNAVAILABLE,
                )
                ExistingProjectAdmissionFailure.GradleModelIncomplete -> deferred(
                    IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
                )
                ExistingProjectAdmissionFailure.DumbMode -> deferred(
                    IdeEndpointDeferredReadiness.DUMB_MODE,
                )
                else -> rejected(IdeEndpointStartupFailure.ProjectRejected(failure))
            }
        }

    private fun deferred(readiness: IdeEndpointDeferredReadiness) =
        IdeEndpointStartup.Deferred(readiness)

    private fun rejected(failure: IdeEndpointStartupFailure) =
        IdeEndpointStartup.Rejected(failure)
}
