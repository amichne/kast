package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.runtime.ControllerApprovedPlan
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGateway
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGrant
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRejection
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prepares the exact workspace before reading its immutable plan, without a mutation permit. */
internal class KastHostedPlanApprovalGateway(
    private val options: KastProviderOptions,
    userHome: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HostedPlanApprovalGateway {
    private val signer = EnrolledPlanApprovalSigner(userHome)

    override suspend fun prepare(
        request: HostedPlanApprovalRequest
    ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalRejection> {
        when (val available = withContext(ioDispatcher) { signer.availability() }) {
            is Refinement.Rejected -> return available
            is Refinement.Refined -> Unit
        }
        val root =
            when (
                val selected =
                    kotlinx.coroutines.runInterruptible(ioDispatcher) {
                        options.roots.discover(request.invocation.workingDirectory.path)
                    }
            ) {
                is CanonicalRootDiscovery.Discovered -> selected.root
                is CanonicalRootDiscovery.Rejected ->
                    return Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
            }
        val identity =
            when (val admitted = HostedPlanIdentity.parse("plan:${request.planIdentity}")) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
            }
        val kind =
            when (request.operation) {
                HostedChangeApprovalOperation.APPLY -> HostedMutationOperation.CHANGE_APPLY
                HostedChangeApprovalOperation.RECOVER -> HostedMutationOperation.CHANGE_RECOVER
            }
        val response =
            when (
                val demanded =
                    options.workspaceDemand.query(
                        root,
                        ExistingIdeOperation.ApprovalPreparation(kind, identity),
                    )
            ) {
                is WorkspaceDemandResult.Native -> demanded.exchange
                is WorkspaceDemandResult.Rejected ->
                    return Refinement.Rejected(HostedPlanApprovalRejection.Workspace(demanded.failure))
            }
        return when (response) {
            is ExistingIdeExchange.Received -> KastHostedPlanChallengeDecoder.decode(request, response.document.value)
            is ExistingIdeExchange.Rejected,
            is ExistingIdeExchange.HostRejected,
            is ExistingIdeExchange.Semantic -> Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
        }
    }

    override suspend fun redeem(
        approval: ControllerApprovedPlan
    ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> =
        withContext(ioDispatcher) { signer.sign(approval) }
}
