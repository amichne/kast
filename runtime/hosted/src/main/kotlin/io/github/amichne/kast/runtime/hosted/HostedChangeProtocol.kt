package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.protocol.CanonicalLiveChangePlanProtocol
import io.github.amichne.kast.change.protocol.LiveChangePlanRequestAdmission
import io.github.amichne.kast.evidence.contract.HostedWorkspaceStateLocation
import io.github.amichne.kast.evidence.contract.KastUserStateRoot
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangePlanStore
import io.github.amichne.kast.evidence.sqlite.SqliteLiveChangePlanStoreOpenResult
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import java.nio.file.Path

/** Planning reads end before durable issuance; writes will use a separate admission path. */
internal suspend fun planHostedChange(
    project: Project,
    query: HostedQueryService,
    request: HostedRequest.PlanChange,
): HostedResponse {
    val plans =
        when (val opened = openHostedPlans(request)) {
            is Refinement.Refined -> opened.value
            is Refinement.Rejected -> return rejectedHostedPlan(opened.failure)
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

private fun openHostedPlans(
    request: HostedRequest.PlanChange
): Refinement<SqliteLiveChangePlanStore, ChangePlanRejection> {
    val state =
        when (
            val result = KastUserStateRoot.parse(Path.of(System.getProperty("user.home")).resolve(".kast").toString())
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return Refinement.Rejected(ChangePlanRejection.RECOVERY_REQUIRED)
        }
    val location =
        when (val result = HostedWorkspaceStateLocation.locate(state, request.root)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return Refinement.Rejected(ChangePlanRejection.RECOVERY_REQUIRED)
        }
    val plans =
        when (val result = SqliteLiveChangePlanStore.open(location.mutationDatabase)) {
            is SqliteLiveChangePlanStoreOpenResult.Opened -> result.store
            is SqliteLiveChangePlanStoreOpenResult.Rejected -> {
                Logger.getInstance(HostedEndpointService::class.java)
                    .info("kast_change stage=PLAN_STORAGE outcome=REJECTED failure=${result.failure.name}")
                return Refinement.Rejected(ChangePlanRejection.RECOVERY_REQUIRED)
            }
        }
    return Refinement.Refined(plans)
}

private fun rejectedHostedPlan(failure: ChangePlanRejection): HostedResponse =
    HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changePlan, OperationOutcome.Rejected(failure))
