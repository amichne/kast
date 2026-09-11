package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.runtime.ControllerApprovedPlan
import io.github.amichne.kast.appserver.runtime.HostedChangeApprovalOperation
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGateway
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGrant
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalRequest
import io.github.amichne.kast.kernel.ReadLimitParameter
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
        val input =
            when (val admitted = BrokerProcessInput.Document.admit(request.arguments.toString(), options.readLimits)) {
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
                is Refinement.Refined -> admitted.value
            }
        val command =
            listOf(
                "change",
                when (request.operation) {
                    HostedChangeApprovalOperation.APPLY -> "apply"
                    HostedChangeApprovalOperation.RECOVER -> "recover"
                },
                "--hosted-approval-prepare",
            )
        val process =
            when (
                val admitted =
                    BrokerProcessRequest.admit(
                        executable = options.executable,
                        arguments = command,
                        workingDirectory = request.invocation.workingDirectory,
                        maximumOutputBytes = options.readLimits[ReadLimitParameter.PROVIDER_OUTPUT_BYTES].value,
                        timeoutMillis = options.qualificationTimeoutMillis,
                        input = input,
                        limits = options.readLimits,
                    )
            ) {
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.UNAVAILABLE)
                is Refinement.Refined -> admitted.value
            }
        return when (val completed = options.processExecutor.execute(process)) {
            is BrokerProcessExecution.Rejected -> Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
            is BrokerProcessExecution.Completed ->
                if (completed.exitCode != 0) Refinement.Rejected(HostedPlanApprovalFailure.PLAN_UNAVAILABLE)
                else KastHostedPlanChallengeDecoder.decode(request, completed.stdout)
        }
    }

    override suspend fun redeem(
        approval: ControllerApprovedPlan
    ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> =
        withContext(ioDispatcher) { signer.sign(approval) }
}
