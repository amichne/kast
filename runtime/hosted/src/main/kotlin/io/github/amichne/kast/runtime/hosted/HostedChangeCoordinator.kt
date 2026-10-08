package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Project-owned effects are serialized after exact-plan lookup through the private local endpoint. */
internal class HostedChangeCoordinator(private val project: Project, private val query: HostedQueryService) {
    private val mutations = Mutex()

    suspend fun apply(request: HostedRequest.ApplyChange): HostedResponse =
        withHostedMutationPermit(mutations) { applyLocked(request) }

    private suspend fun applyLocked(request: HostedRequest.ApplyChange): HostedResponse {
        val context =
            when (val loaded = loadRequest(request.root, request.request.planIdentity.value)) {
                is Refinement.Refined -> loaded.value
                is Refinement.Rejected -> return HostedResponse.ChangeRejected(loaded.failure)
            }
        val result =
            applyHostedChange(
                project = project,
                query = query,
                resources = context.resources,
                plan = context.plan,
            )
        return HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changeApply, result)
    }

    suspend fun recover(request: HostedRequest.RecoverChange): HostedResponse = mutations.withLock {
        val context =
            when (val loaded = loadRequest(request.root, request.request.planIdentity.value)) {
                is Refinement.Refined -> loaded.value
                is Refinement.Rejected -> return@withLock HostedResponse.ChangeRejected(loaded.failure)
            }
        val result =
            recoverHostedChange(
                project = project,
                query = query,
                resources = context.resources,
                plan = context.plan,
            )
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changeRecover, result)
    }

    private fun loadRequest(
        root: CanonicalWorkspaceRoot,
        identity: String,
    ): Refinement<LoadedHostedChange, HostedChangeFailure> {
        val parsed =
            ChangePlanIdentity.parse(identity)
                ?: return Refinement.Rejected(HostedChangeFailure.Endpoint(HostedEndpointFailure.INVALID_REQUEST))
        return load(root, parsed)
    }

    private fun load(
        root: CanonicalWorkspaceRoot,
        identity: ChangePlanIdentity,
    ): Refinement<LoadedHostedChange, HostedChangeFailure> {
        val resources =
            when (val opened = HostedChangeResources.open(root)) {
                is Refinement.Refined -> opened.value
                is Refinement.Rejected -> return opened
            }
        val plan =
            when (
                val loaded =
                    admitLoadedHostedPlan(root, resources.plans.loadPlan(identity))
                        .observed(HostedChangeStorageStage.PLAN_LOOKUP, HostedChangeStorageObserver.Logger)
            ) {
                is Refinement.Refined -> loaded.value
                is Refinement.Rejected -> return loaded
            }
        return Refinement.Refined(LoadedHostedChange(plan, resources))
    }

    private data class LoadedHostedChange(val plan: LiveChangePlan, val resources: HostedChangeResources)
}
