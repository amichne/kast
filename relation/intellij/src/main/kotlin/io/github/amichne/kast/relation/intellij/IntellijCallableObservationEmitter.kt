package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/** Emits exact symbolic callees and source-less compiler boundaries on the current native read. */
internal class IntellijCallableObservationEmitter(
    private val request: RelationRequest,
    private val subject: PsiNamedElement,
    private val projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    private val collector: IntellijRelationCollector,
    private val incomplete: (CalleeProviderItem, RelationLimitation) -> Boolean,
    private val observation: io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation,
    cache: io.github.amichne.kast.relation.contract.CallbackSummaryCachePort =
        io.github.amichne.kast.relation.contract.CallbackSummaryCachePort.Disabled,
) {
    private val callableContext =
        IntellijCallbackFlowContext(
            scope,
            projection,
            collector::admitCallbackWork,
            observation,
            collector::callbackExpiry,
        )

    private val summaries =
        CallbackParameterSummaries(
            request.budget,
            observation,
            cache,
            supplierReadmit = { previous ->
                readmitCallbackSupplierInventory(previous, request, projection, scope, collector::admitCallbackWork)
            },
        ) { previous ->
            readmitCallbackSummary(previous, request, projection, scope, collector::admitCallbackWork)
        }
    private val namedReferences =
        IntellijNamedCallbackObservationEmitter(callableContext, projection, collector, incomplete, summaries)
    private val supplierReader = IntellijCallbackSupplierInventory(callableContext, summaries)

    fun retainFunctionInvocation(
        candidate: CalleeProviderItem.Reference,
        invocation: IntellijK2ResolvedDeclaration.FunctionInvocation,
    ): Boolean {
        val selected = CalleeProviderItem.CallbackSupplies(invocation.call, candidate.owner)
        val owner =
            when (val admitted = selectedOwner(selected)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(selected, admitted.failure)
            }
        val read = IntellijSelectedCallbackSupplies(callableContext, summaries).read(selected.call, owner)
        return retainSelectedSupplies(selected, owner, read.requireInvocationProof())
    }

    fun retainCallbackSupplies(candidate: CalleeProviderItem.CallbackSupplies): Boolean {
        val owner =
            when (val admitted = selectedOwner(candidate)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(candidate, admitted.failure)
            }
        val read = IntellijSelectedCallbackSupplies(callableContext, summaries).read(candidate.call, owner)
        return retainSelectedSupplies(candidate, owner, read)
    }

    private fun selectedOwner(
        candidate: CalleeProviderItem.CallbackSupplies
    ): Refinement<CompilerGroundedSymbolEvidence, RelationLimitation> {
        val found =
            candidate.owner as? ContainingDeclaration.Found
                ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        val owner =
            callableContext.receiverDeclaration(found.declaration)
                ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        return Refinement.Refined(owner)
    }

    private fun retainSelectedSupplies(
        candidate: CalleeProviderItem.CallbackSupplies,
        owner: CompilerGroundedSymbolEvidence,
        read: SelectedCallbackSuppliesRead,
    ): Boolean {
        val target =
            when (read) {
                SelectedCallbackSuppliesRead.NotRequired -> return collector.dismissProviderItem()
                is SelectedCallbackSuppliesRead.Available -> RelationCallableTarget.CallbackSupplies(read.supplies)
                is SelectedCallbackSuppliesRead.Direct -> RelationCallableTarget.DirectInvocations(read.invocations)
                is SelectedCallbackSuppliesRead.Formal ->
                    when (val formal = selectedFormal(candidate, read.upstream)) {
                        is Refinement.Refined -> formal.value
                        is Refinement.Rejected -> return incomplete(candidate, formal.failure)
                    }
                is SelectedCallbackSuppliesRead.Unavailable ->
                    RelationCallableTarget.UnavailableSupply(
                        io.github.amichne.kast.relation.contract.CallbackSupplyUnavailable(read.cause, read.additional)
                    )
            }
        val occurrence =
            callableContext.occurrence(candidate.call.valueInvocationExpression())
                ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val body =
            callableContext.owner(candidate.call) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val value =
            when (
                val admitted = RelationCallableObservation.fromNativeBoundary(request, occurrence, owner, body, target)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(candidate, RelationLimitation.PROVIDER_INCOMPLETE)
            }
        val accepted = collector.acceptCallableObservation(value)
        if (accepted) {
            if (read is SelectedCallbackSuppliesRead.Available)
                read.supplies.values.forEach { summaries.used(it.summary) }
            supplierUsed(target)
        }
        return accepted
    }

    private fun selectedFormal(
        candidate: CalleeProviderItem.CallbackSupplies,
        upstream: NativeCallbackSupplierFormal.Found,
    ): Refinement<RelationCallableTarget.ParameterInvocation, RelationLimitation> {
        val occurrence =
            callableContext.occurrence(candidate.call.valueInvocationExpression())
                ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        val body =
            callableContext.owner(candidate.call) ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        val invocation =
            when (
                val admitted =
                    io.github.amichne.kast.relation.contract.CallbackParameterInvocation.fromCompiler(
                        occurrence,
                        body,
                        upstream.transfers,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(RelationLimitation.PROVIDER_INCOMPLETE)
            }
        return Refinement.Refined(
            RelationCallableTarget.ParameterInvocation(
                upstream.formal,
                supplierReader.read(upstream.formal, upstream.function, upstream.parameter),
                invocation,
            )
        )
    }

    fun retainNamedReference(candidate: CalleeProviderItem.Reference): Boolean = namedReferences.retain(candidate)

    fun retainParameterInvocation(
        candidate: CalleeProviderItem.Reference,
        resolved: IntellijK2ResolvedDeclaration.ParameterInvocation,
    ): Boolean {
        val evidence =
            when (val result = projection.project(resolved.callable)) {
                is IntellijRelationDeclarationProjection.Projected -> result.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        val endpoint =
            callableContext.endpoint(evidence) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val parameter =
            callableContext.occurrence(resolved.parameter)
                ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val identity =
            when (
                val admitted =
                    CallbackParameterIdentity.fromCompiler(
                        endpoint,
                        resolved.position,
                        parameter,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        val occurrence =
            callableContext.occurrence(resolved.call.valueInvocationExpression())
                ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val body =
            callableContext.owner(resolved.call) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val invocation =
            when (
                val admitted =
                    io.github.amichne.kast.relation.contract.CallbackParameterInvocation.fromCompiler(occurrence, body)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(candidate, RelationLimitation.PROVIDER_INCOMPLETE)
            }
        return retainCallable(
            candidate,
            resolved.call.valueInvocationExpression(),
            RelationCallableTarget.ParameterInvocation(
                identity,
                supplierReader.read(identity, resolved.callable, resolved.parameter),
                invocation,
            ),
        )
    }

    fun retainSourceLessCallable(
        candidate: CalleeProviderItem.Reference,
        callable: SourceLessCallable,
    ): Boolean {
        val libraries = (request.searchScope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE
        val disposition =
            when (callable.moduleKind) {
                SourceLessCallableModuleKind.BUILTINS -> SourceLessCallableDisposition.BUILTIN_BOUNDARY
                SourceLessCallableModuleKind.LIBRARY ->
                    when (libraries) {
                        SymbolLibraryPolicy.EXCLUDE -> SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED
                        SymbolLibraryPolicy.INCLUDE -> SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE
                    }
            }
        return retainCallable(
            candidate,
            candidate.reference.element,
            RelationCallableTarget.SourceLess(callable, disposition),
        )
    }

    private fun retainCallable(
        candidate: CalleeProviderItem.Reference,
        site: PsiElement,
        target: RelationCallableTarget,
    ): Boolean {
        val occurrence =
            callableContext.occurrence(site) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val body = callableContext.owner(site) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val lexicalOwner =
            when (val projected = projection.project(subject)) {
                is IntellijRelationDeclarationProjection.Projected -> projected.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        return when (
            val admitted =
                RelationCallableObservation.fromNativeBoundary(
                    request,
                    occurrence,
                    lexicalOwner,
                    body,
                    target,
                )
        ) {
            is Refinement.Refined -> {
                val accepted = collector.acceptCallableObservation(admitted.value)
                if (accepted) supplierUsed(target)
                accepted
            }
            is Refinement.Rejected -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
        }
    }

    private fun supplierUsed(target: RelationCallableTarget) {
        if (target is RelationCallableTarget.ParameterInvocation) {
            val evidence = target.suppliers
            if (evidence is io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence.Exhaustive)
                summaries.supplierUsed(evidence.inventory)
        }
    }
}
