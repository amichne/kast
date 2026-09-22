package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.runtime.ControllerApprovedPlan
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGateway
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGrant
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The installed CLI reads the owner's immutable plan and issues its challenge without a write permit. */
internal class KastHostedPlanApprovalGateway(
    private val options: KastProviderOptions,
    userHome: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HostedPlanApprovalGateway {
    private val signer = EnrolledPlanApprovalSigner(userHome)

    override suspend fun prepare(
        request: HostedPlanApprovalRequest
    ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> {
        when (val available = withContext(ioDispatcher) { signer.availability() }) {
            is Refinement.Rejected -> return available
            is Refinement.Refined -> Unit
        }
        return kotlinx.coroutines.runInterruptible(ioDispatcher) {
            val root =
                when (val selected = options.roots.discover(request.invocation.workingDirectory.path)) {
                    is io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery.Discovered -> selected.root
                    is io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery.Rejected ->
                        return@runInterruptible Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
                }
            val identity =
                when (
                    val admitted =
                        io.github.amichne.kast.appserver.ide.HostedPlanIdentity.parse("plan:${request.planIdentity}")
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return@runInterruptible Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
                }
            val kind =
                when (request.operation) {
                    HostedChangeApprovalOperation.APPLY ->
                        io.github.amichne.kast.appserver.ide.HostedMutationOperation.CHANGE_APPLY
                    HostedChangeApprovalOperation.RECOVER ->
                        io.github.amichne.kast.appserver.ide.HostedMutationOperation.CHANGE_RECOVER
                }
            when (
                val response =
                    options.ideClient.query(
                        root,
                        io.github.amichne.kast.appserver.ide.ExistingIdeOperation.ApprovalPreparation(kind, identity),
                    )
            ) {
                is io.github.amichne.kast.appserver.ide.ExistingIdeExchange.Received ->
                    KastHostedPlanChallengeDecoder.decode(request, response.document.value)
                is io.github.amichne.kast.appserver.ide.ExistingIdeExchange.Rejected,
                is io.github.amichne.kast.appserver.ide.ExistingIdeExchange.HostRejected,
                is io.github.amichne.kast.appserver.ide.ExistingIdeExchange.Semantic ->
                    Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
            }
        }
    }

    override suspend fun redeem(
        approval: ControllerApprovedPlan
    ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> =
        withContext(ioDispatcher) { signer.sign(approval) }
}
