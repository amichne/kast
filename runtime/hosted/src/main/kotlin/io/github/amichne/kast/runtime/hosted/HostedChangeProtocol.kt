package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.protocol.CanonicalLiveChangePlanProtocol
import io.github.amichne.kast.change.protocol.LiveChangePlanRequestAdmission
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

/** Planning reads end before durable issuance; writes will use a separate admission path. */
internal suspend fun planHostedChange(
    project: Project,
    query: HostedQueryService,
    request: HostedRequest.PlanChange,
): HostedResponse {
    val plans =
        when (val opened = HostedChangeResources.open(request.root)) {
            is Refinement.Refined -> opened.value.plans
            is Refinement.Rejected -> return HostedResponse.ChangeRejected(opened.failure)
        }
    val protocol =
        CanonicalLiveChangePlanProtocol(
            admission =
                LiveChangePlanRequestAdmission { original ->
                    when (
                        val result =
                            query.read(query.endpoint, request.root) { context ->
                                prepareHostedAddDeclaration(project, context, original)
                            }
                    ) {
                        is HostedSemanticReadResult.Completed -> result.value
                        is HostedSemanticReadResult.Rejected -> {
                            Logger.getInstance(HostedEndpointService::class.java)
                                .info(
                                    "kast_change stage=${result.stage.name} outcome=REJECTED " +
                                        "failure=READ_ADMISSION_REJECTED"
                                )
                            Refinement.Rejected(ChangePlanRejection.WORKSPACE_NOT_READY)
                        }
                    }
                },
            plans = plans,
        )
    return HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changePlan, protocol.execute(request.request))
}
