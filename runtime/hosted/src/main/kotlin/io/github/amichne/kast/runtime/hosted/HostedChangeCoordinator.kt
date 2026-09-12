package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.apply.VerifiedLivePlanApproval
import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifecycle-owned effects are serialized only after approval. No approval wait holds this project's permit. */
internal class HostedChangeCoordinator(private val project: Project, private val query: HostedQueryService) :
    AutoCloseable {
    private val mutations = Mutex()
    private val approvals =
        HostedChangeApprovals(query.hostLifetime) { loadHostedApprovalKey(Path.of(System.getProperty("user.home"))) }

    fun prepare(request: HostedRequest.PrepareApproval): HostedResponse =
        when (val loaded = load(request.root, request.identity)) {
            is Refinement.Refined ->
                prepareHostedApprovalResponse(
                    plan = loaded.value.plan,
                    effect = request.effect,
                    owner = query.hostLifetime,
                    approvals = approvals,
                )
            is Refinement.Rejected -> HostedResponse.Rejected(loaded.failure)
        }

    suspend fun apply(request: HostedRequest.ApplyChange): HostedResponse = mutations.withLock {
        val context =
            when (
                val admitted =
                    admit(
                        root = request.root,
                        identity = request.request.planIdentity.value,
                        effect = LiveChangeEffect.CHANGE_APPLY,
                        assertion = request.approval,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return@withLock HostedResponse.Rejected(admitted.failure)
            }
        val result =
            applyHostedChange(
                project = project,
                query = query,
                resources = context.loaded.resources,
                plan = context.loaded.plan,
                approval = context.approval,
            )
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changeApply, result)
    }

    suspend fun recover(request: HostedRequest.RecoverChange): HostedResponse = mutations.withLock {
        val context =
            when (
                val admitted =
                    admit(
                        root = request.root,
                        identity = request.request.planIdentity.value,
                        effect = LiveChangeEffect.CHANGE_RECOVER,
                        assertion = request.approval,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return@withLock HostedResponse.Rejected(admitted.failure)
            }
        val result =
            recoverHostedChange(
                project = project,
                query = query,
                resources = context.loaded.resources,
                plan = context.loaded.plan,
                approval = context.approval,
            )
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.changeRecover, result)
    }

    private fun admit(
        root: CanonicalWorkspaceRoot,
        identity: String,
        effect: LiveChangeEffect,
        assertion: String,
    ): Refinement<ApprovedHostedChange, HostedEndpointFailure> {
        val parsed =
            ChangePlanIdentity.parse(identity) ?: return Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
        val loaded =
            when (val result = load(root, parsed)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        return when (val approved = approvals.consume(loaded.plan, effect, assertion)) {
            is Refinement.Refined -> Refinement.Refined(ApprovedHostedChange(loaded, approved.value))
            is Refinement.Rejected -> {
                Logger.getInstance(HostedChangeCoordinator::class.java)
                    .info("kast_change stage=APPROVAL outcome=REJECTED failure=${approved.failure}")
                Refinement.Rejected(HostedEndpointFailure.APPROVAL_REJECTED)
            }
        }
    }

    private fun load(
        root: CanonicalWorkspaceRoot,
        identity: ChangePlanIdentity,
    ): Refinement<LoadedHostedChange, HostedEndpointFailure> {
        val resources =
            when (val opened = HostedChangeResources.open(root)) {
                is Refinement.Refined -> opened.value
                is Refinement.Rejected -> return opened
            }
        val plan =
            when (val loaded = resources.plans.loadPlan(identity)) {
                is LiveChangePlanLookup.Found -> loaded.plan
                LiveChangePlanLookup.Missing -> return Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
                is LiveChangePlanLookup.Rejected -> return Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE)
            }
        if (plan.basis.observation.reference.workspaceRoot != root)
            return Refinement.Rejected(HostedEndpointFailure.WRONG_ROOT)
        return Refinement.Refined(LoadedHostedChange(plan, resources))
    }

    override fun close() {
        approvals.close()
    }

    private data class LoadedHostedChange(val plan: LiveAddDeclarationChangePlan, val resources: HostedChangeResources)

    private data class ApprovedHostedChange(val loaded: LoadedHostedChange, val approval: VerifiedLivePlanApproval)
}
