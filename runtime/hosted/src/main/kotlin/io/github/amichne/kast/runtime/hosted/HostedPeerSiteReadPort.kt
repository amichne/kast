package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.query.protocol.QueryImpactPeerSelection
import io.github.amichne.kast.query.protocol.acquireSite
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.runtime.hosted.lifecycle.IdeLifecycleApplication
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedExecutionBudgetRequest
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadCompletionPolicy
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadReplayPolicy
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

/** One independently owned read; no source context or observation enters the peer's native ports. */
internal fun interface HostedPeerSiteReadPort {
    suspend fun read(
        selection: QueryImpactPeerSelection,
        grant: RelationBudget,
    ): HostedSemanticReadResult<HostedPeerSiteEvaluation>
}

internal val nativeHostedPeerSiteReads = HostedPeerSiteReadPort { selection, grant ->
    val registry = ApplicationManager.getApplication().getServiceIfCreated(IdeLifecycleApplication::class.java)
    if (registry == null)
        HostedSemanticReadResult.Rejected(
            HostedQueryFailure.PROJECT_UNAVAILABLE,
            HostedQueryStage.REQUEST_ADMISSION,
        )
    else
        registry.withRegisteredPeer(selection.expectedBasis) { project, root ->
            val reader = project.service<HostedQueryService>()
            reader.read(
                reader.endpoint,
                root,
                outcome = { evidence ->
                    when (evidence) {
                        is HostedPeerSiteEvaluation.Selected ->
                            io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.EVALUATED
                        is HostedPeerSiteEvaluation.HostedRejected,
                        is HostedPeerSiteEvaluation.QueryRejected ->
                            io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome.REJECTED
                    }
                },
                executionBudget = grant.childRequest(),
                completion = HostedReadCompletionPolicy.CALLER_ELAPSED,
                replay = HostedReadReplayPolicy.SINGLE_EVALUATION,
            ) { context ->
                admitPeerSite(selection, grant, project, context)
            }
        }
}

private suspend fun admitPeerSite(
    selection: QueryImpactPeerSelection,
    requested: RelationBudget,
    project: Project,
    context: HostedSemanticReadContext,
): HostedPeerSiteEvaluation {
    when (val current = selection.expectedBasis.requireCurrentPeer(context.authority.reference)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return peerAuthorityRejected(current.failure, context)
    }
    val services =
        when (val admitted = admitHostedSemanticServices(project, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return peerAuthorityRejected(admitted.failure, context)
        }
    val bytes =
        RelationByteLimit.parse(
                minOf(
                    requested.returnedBytes.value,
                    context.limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong(),
                )
            )
            .proven()
    val grant = RelationBudget(context.executionBudget.resources, bytes)
    return when (
        val selected =
            selection.acquireSite(
                context.authority,
                services.readReferences,
                services.producerSeeds,
                grant,
            )
    ) {
        is Refinement.Rejected -> HostedPeerSiteEvaluation.QueryRejected(selected.failure)
        is Refinement.Refined ->
            HostedPeerSiteEvaluation.Selected(
                selected.value,
                context.authority,
                grant,
                services.acquisitionWork(),
            )
    }
}

private fun peerAuthorityRejected(
    failure: LiveSemanticReadFailure,
    context: HostedSemanticReadContext,
) =
    HostedPeerSiteEvaluation.HostedRejected(
        HostedResponse.ReadRejected(
            HostedQueryFailure.LiveAuthority(failure),
            HostedQueryStage.CONTENT_REVALIDATION,
            ExecutionBudgetPresence.Present(ExecutionBudgetReport.from(context.executionBudget)),
        )
    )

internal fun ImpactSemanticBasisDocument.Live.requireCurrentPeer(
    current: LiveSemanticReadReference
): Refinement<Unit, LiveSemanticReadFailure> =
    when {
        root.value != current.workspaceRoot.value -> Refinement.Rejected(LiveSemanticReadFailure.WRONG_ROOT)
        host.value != current.host.value.toString() -> Refinement.Rejected(LiveSemanticReadFailure.WRONG_HOST)
        referenceVersion.value != current.version ->
            Refinement.Rejected(LiveSemanticReadFailure.REFERENCE_VERSION_UNSUPPORTED)
        epoch.value != current.epoch.value -> Refinement.Rejected(LiveSemanticReadFailure.EPOCH_MOVED)
        contentView.name != current.contentView.name -> Refinement.Rejected(LiveSemanticReadFailure.INCOMPARABLE_EPOCH)
        else -> Refinement.Refined(Unit)
    }

private fun RelationBudget.childRequest() =
    HostedExecutionBudgetRequest(
        RequestedExecutionBudget(
            elapsed = ExecutionAllowance.Requested(resources.elapsedTimeLimit),
            work = ExecutionAllowance.Requested(resources.workUnitLimit),
            results = ExecutionAllowance.Requested(resources.resultLimit),
            returnedBytes = ExecutionAllowance.Requested(ReturnedByteLimit.parse(returnedBytes.value).proven()),
        )
    )

private fun <Value> Refinement<Value, *>.proven(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted positive peer grant lost its proof")
    }
