package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.project.Project
import io.github.amichne.kast.ide.compatibility.AdmittedIdeHostCompatibilityMetadata
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.change.intellij.HostedChangeAdmission
import io.github.amichne.kast.change.intellij.admitHostedIntellijChangePorts
import io.github.amichne.kast.diagnostic.intellij.HostedDiagnosticAdmission
import io.github.amichne.kast.diagnostic.intellij.admitHostedIntellijDiagnosticPorts
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorCandidate
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointFraming
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointHostKind
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.CanonicalHostedCapabilities
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
import io.github.amichne.kast.runtime.ide.host.HostedChangeRuntimePorts
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntime
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntimeComposition
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntimeCompositionFailure
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntimeCompositionResult
import io.github.amichne.kast.runtime.ide.host.HostedSemanticGenerationIssuance
import io.github.amichne.kast.runtime.ide.host.HostedTopologyAdapterPorts
import io.github.amichne.kast.runtime.ide.host.HostedWorkspaceOperations
import io.github.amichne.kast.relation.intellij.HostedRelationAdmission
import io.github.amichne.kast.relation.intellij.InstalledRelationScopeOperations
import io.github.amichne.kast.relation.intellij.admitHostedIntellijRelationPorts
import io.github.amichne.kast.topology.intellij.HostedTopologyAdmission
import io.github.amichne.kast.topology.intellij.HostedWorkspaceSourceStateAdmission
import io.github.amichne.kast.topology.intellij.admitHostedWorkspaceSourceState
import io.github.amichne.kast.topology.intellij.admitHostedIntellijTopologyPorts
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import java.nio.file.Path

data class IdeEndpointPreparationCandidate(
    val descriptorRoot: IdeEndpointCanonicalRoot,
    val runtime: HostedIdeRuntime,
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
            val runtime = candidate.runtime
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
                        capabilities = CanonicalHostedCapabilities.candidates,
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
    data class HostedRuntimeRejected(
        val cause: HostedIdeRuntimeCompositionFailure,
    ) : IdeEndpointStartupFailure
    data object HostedEffectsRejected : IdeEndpointStartupFailure
    data object HostedWorkspaceRejected : IdeEndpointStartupFailure
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

internal object IdeEndpointReadRuntimeAdmission {
    /**
     * Proof transition: `HostedIdeReadRuntimePreparationFailure -> IdeEndpointStartup`.
     *
     * A partial exact-four-route runtime cannot publish, but may be observed while IntelliJ moves
     * through a Gradle model or smart-mode transition. It therefore remains a closed deferred
     * readiness state so the next installed lifecycle signal can re-run the full admission.
     */
    fun admit(failure: HostedIdeReadRuntimePreparationFailure): IdeEndpointStartup = when (failure) {
        HostedIdeReadRuntimePreparationFailure.PARTIAL_RUNTIME -> IdeEndpointStartup.Deferred(
            IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
        )
    }
}

/** Live project-service startup boundary with no import, refresh, traversal, or fallback path. */
internal object LiveIdeEndpointStartup {
    /**
     * Proof transition: `(Project, ProjectEndpointGenerationSource) -> IdeEndpointStartup`.
     *
     * Refines the existing Project through the compiled host-compatibility tuple and [HostedIdeReadProject],
     * preserves transient cached-readiness failures as [IdeEndpointStartup.Deferred], and consumes
     * a generation only after complete hosted runtime construction. All other expected
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
        val workspaceModel = when (val admission = admitHostedWorkspaceModel(model)) {
            is HostedWorkspaceModelAdmissionResult.Admitted -> admission.admission
            is HostedWorkspaceModelAdmissionResult.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedWorkspaceRejected,
            )
        }
        val stateRoot = when (val parsed = System.getProperty("user.home")?.let { home ->
            KastUserStateRoot.parse(
                Path.of(home).resolve(".local/share/kast").toAbsolutePath().normalize().toString(),
            )
        }) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected, null -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val stateLocation = when (val located = HostedWorkspaceStateLocation.locate(
            stateRoot,
            workspaceRoot,
        )) {
            is Refinement.Refined -> located.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val sourceState = when (val admission = admitHostedWorkspaceSourceState(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
            workspaceModel.sourceRoots,
        )) {
            is HostedWorkspaceSourceStateAdmission.Admitted -> admission.sourceState
            is HostedWorkspaceSourceStateAdmission.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedEffectsRejected,
            )
        }
        val evidenceGeneration = when (val issued =
            HostedIdeRuntimeComposition.issueSemanticGeneration(
                stateLocation,
                sourceState,
            )
        ) {
            is HostedSemanticGenerationIssuance.Issued -> issued.generation
            HostedSemanticGenerationIssuance.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostConfigurationUnavailable,
            )
        }
        val workspaceAdmission = when (val admission = workspaceModel.reconcile(sourceState)) {
            is HostedWorkspaceAdmissionResult.Admitted -> admission.admission
            is HostedWorkspaceAdmissionResult.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedWorkspaceRejected,
            )
        }
        val readRuntime = when (val preparation = composition.activate(evidenceGeneration)) {
            is HostedIdeReadRuntimePreparation.Prepared -> preparation.runtime
            is HostedIdeReadRuntimePreparation.Rejected -> return IdeEndpointReadRuntimeAdmission.admit(
                preparation.failure,
            )
        }
        val hostedWorkspace = HostedWorkspaceOperations(
            workspaceAdmission.publish(evidenceGeneration),
        )
        val topologyPorts = when (val admission = admitHostedIntellijTopologyPorts(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
            hostedWorkspace,
        )) {
            is HostedTopologyAdmission.Admitted -> admission.ports
            is HostedTopologyAdmission.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedEffectsRejected,
            )
        }
        val relationPorts = when (val admission = admitHostedIntellijRelationPorts(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
            hostedWorkspace,
            InstalledRelationScopeOperations { workspaceAdmission.scope },
        )) {
            is HostedRelationAdmission.Admitted -> admission.ports
            is HostedRelationAdmission.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedEffectsRejected,
            )
        }
        val diagnosticPorts = when (val admission = admitHostedIntellijDiagnosticPorts(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
            hostedWorkspace,
        )) {
            is HostedDiagnosticAdmission.Admitted -> admission.ports
            is HostedDiagnosticAdmission.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedEffectsRejected,
            )
        }
        val changePorts = when (val admission = admitHostedIntellijChangePorts(
            project,
            workspaceRoot,
            metadata.candidate,
            metadata.compatibilityPolicy,
        )) {
            is HostedChangeAdmission.Admitted -> admission.ports
            is HostedChangeAdmission.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedEffectsRejected,
            )
        }
        val runtime = when (val created = HostedIdeRuntimeComposition.create(
            readRuntime,
            hostedWorkspace,
            stateLocation,
            HostedTopologyAdapterPorts(topologyPorts.candidates, topologyPorts.fileExtractor),
            HostedChangeRuntimePorts(
                relationPorts.compiler,
                diagnosticPorts.compiler,
                changePorts.sourceObserver,
                changePorts.sourceWriter,
                changePorts.sourceRollback,
                changePorts.intentCompiler,
                changePorts.semanticObserver,
            ),
        )) {
            is HostedIdeRuntimeCompositionResult.Created -> created.runtime
            is HostedIdeRuntimeCompositionResult.Rejected -> return rejected(
                IdeEndpointStartupFailure.HostedRuntimeRejected(created.failure),
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
