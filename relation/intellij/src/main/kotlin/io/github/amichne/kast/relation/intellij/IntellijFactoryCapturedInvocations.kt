package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty

private data class NativeCaptureBinding(
    val declaration: PsiElement,
    val transfers: List<ValueTransfer>,
    val visited: Set<PsiElement>,
)

private data class NativeCaptureRoute(val site: ValueSite, val transfers: List<ValueTransfer>)

private sealed interface NativeCapturedUse {
    data object Other : NativeCapturedUse

    data class Invocation(val value: CallbackParameterInvocation) : NativeCapturedUse

    data class Alias(val binding: NativeCaptureBinding) : NativeCapturedUse
}

/** Exhausts compiler-confirmed reads through immutable aliases into exact returned anonymous bodies. */
internal class IntellijFactoryCapturedInvocations(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        function: KtNamedFunction,
        position: Int,
        bodies: Set<RelationCallableBody.Anonymous>,
    ): Refinement<List<CallbackParameterInvocation>, CallbackInvocationFlowCause> {
        val endpoint =
            when (val target = context.target(function)) {
                is Refinement.Refined -> target.value
                is Refinement.Rejected -> return target
            }
        return Enumeration(function, endpoint, bodies).read(position)
    }

    private inner class Enumeration(
        private val function: KtNamedFunction,
        private val endpoint: RelationEndpoint.Resolved,
        private val bodies: Set<RelationCallableBody.Anonymous>,
    ) {
        fun read(position: Int): Refinement<List<CallbackParameterInvocation>, CallbackInvocationFlowCause> {
            val pending = ArrayDeque<NativeCaptureBinding>()
            pending.add(NativeCaptureBinding(function.valueParameters[position], emptyList(), emptySet()))
            val result = mutableListOf<CallbackParameterInvocation>()
            while (pending.isNotEmpty()) {
                val binding = pending.removeFirst()
                if (binding.declaration in binding.visited) return rejected(CallbackInvocationFlowCause.CALLBACK_CYCLE)
                val references =
                    when (val read = references(binding.declaration, function)) {
                        is Refinement.Refined -> read.value
                        is Refinement.Rejected -> return read
                    }
                for (reference in references) when (val read = use(reference, binding)) {
                    is Refinement.Rejected -> return read
                    is Refinement.Refined -> read.value.retain(pending, result)
                }
            }
            return Refinement.Refined(result.distinct())
        }

        private fun NativeCapturedUse.retain(
            pending: ArrayDeque<NativeCaptureBinding>,
            result: MutableList<CallbackParameterInvocation>,
        ) {
            when (this) {
                NativeCapturedUse.Other -> Unit
                is NativeCapturedUse.Invocation -> result += value
                is NativeCapturedUse.Alias -> pending.add(binding)
            }
        }

        private fun use(
            reference: KtReference,
            binding: NativeCaptureBinding,
        ): Refinement<NativeCapturedUse, CallbackInvocationFlowCause> {
            when (val permit = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return permit
            }
            when (val resolved = resolveLocalValueReference(reference)) {
                NativeLocalValueReferenceResolution.Unresolved ->
                    return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
                is NativeLocalValueReferenceResolution.Resolved ->
                    if (resolved.declaration.originalElement != binding.declaration.originalElement)
                        return Refinement.Refined(NativeCapturedUse.Other)
            }
            val read =
                reference.element as? KtNameReferenceExpression
                    ?: return rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
            val route =
                when (val readRoute = route(read, binding)) {
                    is Refinement.Refined -> readRoute.value
                    is Refinement.Rejected -> return readRoute
                }
            val call = context.parameterInvocation(read)
            if (call == null) return alias(read, binding, route)
            val owner =
                context.owner(call) as? RelationCallableBody.Anonymous
                    ?: return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            if (owner !in bodies) return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            val occurrence =
                context.occurrence(call.valueInvocationExpression())
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            return when (val admitted = CallbackParameterInvocation.fromCompiler(occurrence, owner, route.transfers)) {
                is Refinement.Refined -> Refinement.Refined(NativeCapturedUse.Invocation(admitted.value))
                is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        }

        private fun route(
            read: KtNameReferenceExpression,
            binding: NativeCaptureBinding,
        ): Refinement<NativeCaptureRoute, CallbackInvocationFlowCause> {
            val role = if (binding.transfers.isEmpty()) ValueRole.ExpressionResult else ValueRole.LocalRead
            val site =
                context.site(read, endpoint, role)
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            if (binding.transfers.isEmpty()) return Refinement.Refined(NativeCaptureRoute(site, emptyList()))
            return when (
                val edge =
                    ValueTransfer.fromCompiler(binding.transfers.last().target, site, ValueTransferKind.LOCAL_READ)
            ) {
                is Refinement.Refined -> Refinement.Refined(NativeCaptureRoute(site, binding.transfers + edge.value))
                is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            }
        }

        private fun alias(
            read: KtNameReferenceExpression,
            binding: NativeCaptureBinding,
            route: NativeCaptureRoute,
        ): Refinement<NativeCapturedUse, CallbackInvocationFlowCause> {
            var expression: KtExpression = read
            while (expression.parent is KtParenthesizedExpression) expression =
                expression.parent as KtParenthesizedExpression
            val property =
                expression.parent as? KtProperty ?: return rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
            if (property.isVar || property.hasDelegate() || !property.isLocal)
                return rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
            if (property.initializer !== expression) return rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
            val destination =
                context.site(property, endpoint, ValueRole.LocalBinding)
                    ?: return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            val edge =
                when (
                    val admitted = ValueTransfer.fromCompiler(route.site, destination, ValueTransferKind.LOCAL_BINDING)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
            return when (
                val retained = summaries.retention.admit(route.site.retainedBytes + destination.retainedBytes)
            ) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        NativeCapturedUse.Alias(
                            NativeCaptureBinding(
                                property,
                                route.transfers + edge,
                                binding.visited + binding.declaration,
                            )
                        )
                    )
                is Refinement.Rejected -> retained
            }
        }
    }

    private fun references(
        declaration: PsiElement,
        function: KtNamedFunction,
    ): Refinement<List<KtReference>, CallbackInvocationFlowCause> {
        val references = mutableListOf<KtReference>()
        var outcome: Refinement<Unit, CallbackInvocationFlowCause> = Refinement.Refined(Unit)
        val exhausted =
            ReferencesSearch.search(declaration, LocalSearchScope(function))
                .forEach(
                    Processor { reference ->
                        outcome = retainReference(reference, references)
                        outcome is Refinement.Refined
                    }
                )
        return when (val read = outcome) {
            is Refinement.Rejected -> read
            is Refinement.Refined ->
                if (exhausted) Refinement.Refined(references)
                else rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        }
    }

    private fun retainReference(
        reference: com.intellij.psi.PsiReference,
        references: MutableList<KtReference>,
    ): Refinement<Unit, CallbackInvocationFlowCause> {
        when (val permit = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return permit
        }
        when (val retained = summaries.retention.admit(256L)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return retained
        }
        val native =
            reference as? KtReference ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        references += native
        return Refinement.Refined(Unit)
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}
