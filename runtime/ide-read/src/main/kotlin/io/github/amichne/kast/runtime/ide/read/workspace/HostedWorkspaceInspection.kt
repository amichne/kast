package io.github.amichne.kast.runtime.ide.read.workspace

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapabilitySet
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.runtime.ide.read.dispatch.WorkspaceInspectReadPort
import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadProject
import io.github.amichne.kast.runtime.ide.read.preparation.HostedProjectCurrentRead

/** The sole host kind admitted by the passive reused-Project workspace route. */
enum class HostedWorkspaceKind {
    IDE_PROJECT,
}

/** Closed workspace inspection construction failures. */
enum class HostedWorkspaceInspectionPreparationFailure {
    NON_IDE_PROJECT_HOST,
    ROOT_UNREPRESENTABLE,
}

/** Closed result of preparing the existing-Project workspace inspection capability. */
sealed interface HostedWorkspaceInspectionPreparation {
    data class Prepared(
        val inspection: HostedWorkspaceInspection,
    ) : HostedWorkspaceInspectionPreparation

    data class Rejected(
        val failure: HostedWorkspaceInspectionPreparationFailure,
    ) : HostedWorkspaceInspectionPreparation
}

/** Boundary candidates used to exercise the named workspace inspection misuse without widening production. */
internal sealed interface HostedWorkspaceInspectionCandidate {
    data class ExistingProject(
        val project: HostedIdeReadProject,
        val generation: EvidenceGeneration,
    ) : HostedWorkspaceInspectionCandidate

    data object IsolatedRuntime : HostedWorkspaceInspectionCandidate
}

/** Exact-root `workspace.inspect` authority backed only by the retained IDE Project. */
class HostedWorkspaceInspection private constructor(
    private val project: HostedIdeReadProject,
    private val generation: EvidenceGeneration,
    val canonicalRoot: ProtocolText,
) : WorkspaceInspectReadPort {
    val hostKind: HostedWorkspaceKind = HostedWorkspaceKind.IDE_PROJECT
    val capabilities: IdeHostCapabilitySet = project.compatibility.capabilities

    /**
     * Proof transition: `WorkspaceInspectRequest -> OperationOutcome<WorkspaceInspectResult,
     * WorkspaceInspectQualification, WorkspaceInspectRejection>`.
     *
     * Re-admits the retained Project's just-observed opaque epoch before returning the exact root
     * as READY. Missing current read proof remains a closed rejection; no repair or stronger
     * effect is available to this capability. Raw root text leaves only in the protocol payload.
     */
    override suspend fun execute(
        request: WorkspaceInspectRequest,
    ): OperationOutcome<
        WorkspaceInspectResult,
        WorkspaceInspectQualification,
        WorkspaceInspectRejection,
        > = when (val read = project.admitCurrentRead()) {
        is HostedProjectCurrentRead.Admitted -> if (
            read.capability.canonicalRoot.value == canonicalRoot.value
        ) {
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    operation = CanonicalOperation.WORKSPACE_INSPECT.id,
                    generation = generation,
                    payload = WorkspaceInspectResult(
                        canonicalRoot = canonicalRoot,
                        state = WorkspaceStateDocument.READY,
                    ),
                ),
            )
        } else {
            OperationOutcome.Rejected(WorkspaceInspectRejection.ROOT_UNAVAILABLE)
        }
        is HostedProjectCurrentRead.EpochRejected,
        is HostedProjectCurrentRead.FreshnessRejected,
            -> OperationOutcome.Rejected(WorkspaceInspectRejection.RUNTIME_BLOCKED)
    }

    companion object {
        /**
         * Proof transition: `(HostedIdeReadProject, EvidenceGeneration) ->
         * HostedWorkspaceInspectionPreparation`.
         *
         * Establishes one IDE_PROJECT route with the admitted exact capability set and bounded
         * protocol root. [HostedWorkspaceInspectionPreparationFailure] closes unrepresentable
         * root state. Raw root extraction is confined to this protocol projection boundary.
         */
        fun prepare(
            project: HostedIdeReadProject,
            generation: EvidenceGeneration,
        ): HostedWorkspaceInspectionPreparation = prepare(
            HostedWorkspaceInspectionCandidate.ExistingProject(project, generation),
        )

        /** Test-visible closed candidate transition used by the graph-named misuse. */
        @JvmSynthetic
        internal fun prepare(
            candidate: HostedWorkspaceInspectionCandidate,
        ): HostedWorkspaceInspectionPreparation = when (candidate) {
            HostedWorkspaceInspectionCandidate.IsolatedRuntime ->
                HostedWorkspaceInspectionPreparation.Rejected(
                    HostedWorkspaceInspectionPreparationFailure.NON_IDE_PROJECT_HOST,
                )
            is HostedWorkspaceInspectionCandidate.ExistingProject -> when (
                val root = ProtocolText.parse(candidate.project.canonicalRoot.value)
            ) {
                is Refinement.Refined -> HostedWorkspaceInspectionPreparation.Prepared(
                    HostedWorkspaceInspection(
                        candidate.project,
                        candidate.generation,
                        root.value,
                    ),
                )
                is Refinement.Rejected -> HostedWorkspaceInspectionPreparation.Rejected(
                    HostedWorkspaceInspectionPreparationFailure.ROOT_UNREPRESENTABLE,
                )
            }
        }
    }
}
