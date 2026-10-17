package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackResultMembership
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.CallbackResultSite
import io.github.amichne.kast.relation.contract.CallbackResultSites
import io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.NamedCallbackReference
import io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path

internal sealed interface CallbackResultProjectionRead {
    data class Admitted(val membership: CallbackResultMembership) : CallbackResultProjectionRead

    data object Excluded : CallbackResultProjectionRead

    data class Unproven(val cause: CallbackInvocationFlowCause) : CallbackResultProjectionRead
}

internal sealed interface CallbackCallableProjectionRead {
    data class Kept(val target: RelationCallableTarget) : CallbackCallableProjectionRead

    data object Excluded : CallbackCallableProjectionRead
}

/** Result membership uses the original native scope after the independent callback proof. */
internal class IntellijCallbackResultProjection(
    private val project: Project,
    private val selected: CompiledRelationScope,
    private val collector: IntellijRelationCollector,
    private val observation: IntellijReadObservation,
    private val cancellationCheck: () -> Unit = ProgressManager::checkCanceled,
    private val fileLookup: (SymbolDiscoveryFileIdentity) -> VirtualFile? = ::callbackProjectionFile,
) {
    fun callable(target: RelationCallableTarget): CallbackCallableProjectionRead =
        when (target) {
            is RelationCallableTarget.NamedReference ->
                when (val read = reference(target.reference)) {
                    is CallbackResultProjectionRead.Admitted ->
                        CallbackCallableProjectionRead.Kept(
                            target.copy(resultProjection = CallbackResultProjection.Admitted(read.membership))
                        )
                    is CallbackResultProjectionRead.Unproven ->
                        CallbackCallableProjectionRead.Kept(
                            target.copy(resultProjection = CallbackResultProjection.Unproven(read.cause))
                        )
                    CallbackResultProjectionRead.Excluded -> CallbackCallableProjectionRead.Excluded
                }
            is RelationCallableTarget.ParameterInvocation ->
                when (val read = parameter(target.suppliers)) {
                    is CallbackResultProjectionRead.Admitted ->
                        CallbackCallableProjectionRead.Kept(
                            target.copy(resultProjection = CallbackResultProjection.Admitted(read.membership))
                        )
                    is CallbackResultProjectionRead.Unproven ->
                        CallbackCallableProjectionRead.Kept(
                            target.copy(resultProjection = CallbackResultProjection.Unproven(read.cause))
                        )
                    CallbackResultProjectionRead.Excluded -> CallbackCallableProjectionRead.Excluded
                }
            is RelationCallableTarget.DirectInvocations,
            is RelationCallableTarget.CallbackSupplies,
            is RelationCallableTarget.UnavailableSupply,
            is RelationCallableTarget.UnavailableReference,
            is RelationCallableTarget.SourceLess -> CallbackCallableProjectionRead.Kept(target)
        }.also { selected ->
            when (selected) {
                CallbackCallableProjectionRead.Excluded ->
                    observation.count(IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED)
                is CallbackCallableProjectionRead.Kept ->
                    when (val kept = selected.target) {
                        is RelationCallableTarget.NamedReference -> observe(kept.resultProjection)
                        is RelationCallableTarget.ParameterInvocation -> observe(kept.resultProjection)
                        is RelationCallableTarget.DirectInvocations,
                        is RelationCallableTarget.CallbackSupplies,
                        is RelationCallableTarget.UnavailableSupply,
                        is RelationCallableTarget.UnavailableReference,
                        is RelationCallableTarget.SourceLess -> Unit
                    }
            }
        }

    fun callback(value: RelationCallbackObservation): CallbackResultProjectionRead {
        val sites = CallbackResultSites.callback(value.meaning, value.occurrence, value.target, value.flow)
        return when (val result = select(sites)) {
            is CallbackResultProjectionRead.Admitted,
            is CallbackResultProjectionRead.Unproven -> result
            CallbackResultProjectionRead.Excluded ->
                when (CompleteStaticCallbackGraph.admit(value)) {
                    is Refinement.Refined -> result
                    is Refinement.Rejected ->
                        CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                }
        }.also { result ->
            when (result) {
                is CallbackResultProjectionRead.Admitted ->
                    observation.count(IntellijReadCounter.CALLBACK_RESULTS_ADMITTED)
                is CallbackResultProjectionRead.Unproven ->
                    observation.count(IntellijReadCounter.CALLBACK_RESULTS_UNPROVEN)
                CallbackResultProjectionRead.Excluded ->
                    observation.count(IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED)
            }
        }
    }

    fun parameter(suppliers: CallbackSupplierInventoryEvidence): CallbackResultProjectionRead =
        when (suppliers) {
            is CallbackSupplierInventoryEvidence.Unavailable -> CallbackResultProjectionRead.Unproven(suppliers.cause)
            is CallbackSupplierInventoryEvidence.Exhaustive ->
                select(CallbackResultSites.suppliers(suppliers.inventory))
        }

    fun reference(reference: NamedCallbackReference): CallbackResultProjectionRead {
        val result = select(CallbackResultSites.reference(selected.request.meaning, reference))
        if (result != CallbackResultProjectionRead.Excluded) return result
        return when (val flow = reference.flow) {
            is NamedCallbackReferenceFlow.Supplied,
            is NamedCallbackReferenceFlow.Direct -> result
            is NamedCallbackReferenceFlow.Unavailable -> CallbackResultProjectionRead.Unproven(flow.cause)
            is NamedCallbackReferenceFlow.Immutable ->
                if (flow.flow.scan == CallbackInvocationScan.EXHAUSTIVE && flow.flow.obligations.isEmpty()) result
                else CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        }
    }

    private fun select(sites: List<CallbackResultSite>): CallbackResultProjectionRead {
        var outcome: CallbackResultProjectionRead = CallbackResultProjectionRead.Excluded
        for (site in sites) {
            when (permit(site)) {
                CallbackResultSiteAdmission.READY -> Unit
                CallbackResultSiteAdmission.EXCLUDED -> {
                    observation.count(IntellijReadCounter.CALLBACK_RESULT_SITES_EXAMINED)
                    continue
                }
                CallbackResultSiteAdmission.WORK_LIMIT_REACHED ->
                    return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
                CallbackResultSiteAdmission.TIME_LIMIT_REACHED ->
                    return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.TIME_LIMIT_REACHED)
            }
            observation.count(IntellijReadCounter.CALLBACK_RESULT_SITES_EXAMINED)
            when (val membership = membership(site)) {
                is CallbackResultProjectionRead.Admitted -> return membership
                is CallbackResultProjectionRead.Unproven -> outcome = membership
                CallbackResultProjectionRead.Excluded -> Unit
            }
        }
        return outcome
    }

    private fun observe(projection: CallbackResultProjection) {
        when (projection) {
            is CallbackResultProjection.Admitted -> observation.count(IntellijReadCounter.CALLBACK_RESULTS_ADMITTED)
            is CallbackResultProjection.Unproven -> observation.count(IntellijReadCounter.CALLBACK_RESULTS_UNPROVEN)
        }
    }

    private fun membership(site: CallbackResultSite): CallbackResultProjectionRead {
        val file =
            observation.call(IntellijReadCall.VFS_FIND_FILE) {
                fileLookup(site.occurrence.file)
            } ?: return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        when (selected.admitProviderSite(file)) {
            RelationProviderScopeAdmission.ADMITTED -> Unit
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> return CallbackResultProjectionRead.Excluded
            RelationProviderScopeAdmission.UNAVAILABLE ->
                return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        }
        if (selected.constraints.packageName != null) {
            val psi =
                observation.call(IntellijReadCall.PSI_FIND_FILE) { PsiManager.getInstance(project).findFile(file) }
                    ?: return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            when (selected.constraints.packageName.admitPackage { psi.relationPackageEvidence() }) {
                IntellijRelationPackageAdmission.ADMITTED -> Unit
                IntellijRelationPackageAdmission.OUTSIDE_SCOPE -> return CallbackResultProjectionRead.Excluded
                IntellijRelationPackageAdmission.UNSUPPORTED ->
                    return CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            }
        }
        return when (val admitted = CallbackResultMembership.fromNativeScope(selected.request, site)) {
            is Refinement.Refined -> CallbackResultProjectionRead.Admitted(admitted.value)
            is Refinement.Rejected -> CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        }
    }

    private fun permit(site: CallbackResultSite): CallbackResultSiteAdmission {
        cancellationCheck()
        if (selected.excludesKnownSource(site.occurrence.file)) {
            return when (collector.callbackExpiry()) {
                CallbackExpiryAdmission.CURRENT -> CallbackResultSiteAdmission.EXCLUDED
                CallbackExpiryAdmission.EXPIRED -> CallbackResultSiteAdmission.TIME_LIMIT_REACHED
            }
        }
        return when (collector.admitCallbackWork()) {
            CallbackWorkAdmission.READY -> CallbackResultSiteAdmission.READY
            CallbackWorkAdmission.WORK_LIMIT_REACHED -> CallbackResultSiteAdmission.WORK_LIMIT_REACHED
            CallbackWorkAdmission.TIME_LIMIT_REACHED -> CallbackResultSiteAdmission.TIME_LIMIT_REACHED
        }
    }

    private enum class CallbackResultSiteAdmission {
        READY,
        EXCLUDED,
        WORK_LIMIT_REACHED,
        TIME_LIMIT_REACHED,
    }
}

private fun callbackProjectionFile(identity: SymbolDiscoveryFileIdentity): VirtualFile? =
    when (identity) {
        is SymbolDiscoveryFileIdentity.Workspace ->
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(identity.path.value))
        is SymbolDiscoveryFileIdentity.External -> VirtualFileManager.getInstance().findFileByUrl(identity.url.value)
    }
