package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Native backward supply resolution. Every alternative retains the original callable and ordered transport. */
internal class IntellijImmutableCallbackValueResolver(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    private data class Arrival(val target: ValueSite, val kind: ValueTransferKind)

    private data class Pending(
        val expression: KtExpression,
        val arrivals: List<Arrival>,
        val visited: Set<PsiElement>,
    )

    fun resolve(
        expression: KtExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        activeFactories: Set<KtNamedFunction> = emptySet(),
    ): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
        val owner =
            context.endpoint(lexicalOwner)
                ?: return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return Resolution(owner, lexicalOwner, activeFactories).read(expression)
    }

    private inner class Resolution(
        private val owner: RelationEndpoint,
        private val lexicalOwner: CompilerGroundedSymbolEvidence,
        private val activeFactories: Set<KtNamedFunction>,
    ) {
        private val pending = ArrayDeque<Pending>()
        private val values = mutableListOf<ImmutableCallbackValue>()

        fun read(expression: KtExpression): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
            pending.add(Pending(expression, emptyList(), emptySet()))
            while (pending.isNotEmpty()) {
                when (val permit = context.permit()) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return permit
                }
                val item = pending.removeFirst()
                if (item.expression in item.visited) return rejected(CallbackInvocationFlowCause.CALLBACK_CYCLE)
                when (val result = visit(item.copy(visited = item.visited + item.expression))) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return result
                }
            }
            return Refinement.Refined(values.distinct())
        }

        private fun visit(item: Pending): Refinement<Unit, CallbackInvocationFlowCause> =
            when (val value = item.expression) {
                is KtParenthesizedExpression -> unwrap(value.expression, item)
                is KtLabeledExpression -> unwrap(value.baseExpression, item)
                is KtNameReferenceExpression -> local(value, item)
                is KtIfExpression -> conditional(value, item)
                is KtWhenExpression -> conditional(value, item)
                is KtCallExpression -> returned(value, item)
                is KtQualifiedExpression -> returned(value.selectorExpression as? KtCallExpression, item)
                is KtLambdaExpression -> anonymous(value.functionLiteral, item)
                is KtNamedFunction ->
                    if (value.name == null) anonymous(value, item)
                    else rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                is KtCallableReferenceExpression -> named(value, item)
                else -> rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            }

        private fun unwrap(inner: KtExpression?, item: Pending): Refinement<Unit, CallbackInvocationFlowCause> {
            if (inner == null) return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            pending.addFirst(item.copy(expression = inner))
            return Refinement.Refined(Unit)
        }

        private fun local(
            value: KtNameReferenceExpression,
            item: Pending,
        ): Refinement<Unit, CallbackInvocationFlowCause> {
            val property =
                when (val declaration = property(value)) {
                    is Refinement.Refined -> declaration.value
                    is Refinement.Rejected -> return declaration
                }
            val initializer =
                property.initializer ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            if (
                context.owner(property)?.compilerIdentity != lexicalOwner.compilerIdentity ||
                    context.owner(value)?.compilerIdentity != lexicalOwner.compilerIdentity
            )
                return rejected(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            val binding =
                context.site(property, owner, ValueRole.LocalBinding)
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            val read =
                context.site(value, owner, ValueRole.LocalRead)
                    ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            val arrivals =
                listOf(Arrival(binding, ValueTransferKind.LOCAL_BINDING), Arrival(read, ValueTransferKind.LOCAL_READ))
            pending.addFirst(Pending(initializer, arrivals + item.arrivals, item.visited))
            return Refinement.Refined(Unit)
        }

        private fun property(value: KtNameReferenceExpression): Refinement<KtProperty, CallbackInvocationFlowCause> {
            val reference =
                value.references
                    .filterIsInstance<KtReference>()
                    .filterNot { it is KtInvokeFunctionReference }
                    .singleOrNull() ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
            val declaration =
                when (val resolved = resolveLocalValueReference(reference)) {
                    is NativeLocalValueReferenceResolution.Resolved -> resolved.declaration
                    NativeLocalValueReferenceResolution.Unresolved ->
                        return rejected(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
                }
            val property =
                declaration as? KtProperty ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            if (!property.isLocal || property.isVar || property.hasDelegate())
                return rejected(CallbackInvocationFlowCause.STORED_CALLBACK)
            return Refinement.Refined(property)
        }

        private fun conditional(value: KtIfExpression, item: Pending): Refinement<Unit, CallbackInvocationFlowCause> {
            val then = value.then ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            val otherwise = value.`else` ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            return branches(value, listOf(then, otherwise), owner, item.arrivals, item.visited, pending)
        }

        private fun conditional(value: KtWhenExpression, item: Pending): Refinement<Unit, CallbackInvocationFlowCause> {
            if (value.entries.none { it.isElse })
                return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            val alternatives =
                value.entries.map {
                    it.expression ?: return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                }
            return branches(value, alternatives, owner, item.arrivals, item.visited, pending)
        }

        private fun returned(call: KtCallExpression?, item: Pending): Refinement<Unit, CallbackInvocationFlowCause> {
            if (call == null) return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            val returned =
                when (
                    val read =
                        IntellijCallbackFactoryReturn(context, summaries).read(call, lexicalOwner, activeFactories)
                ) {
                    is Refinement.Refined -> read.value
                    is Refinement.Rejected -> return read
                }
            for (result in returned) {
                when (val completed = append(result, item.arrivals)) {
                    is Refinement.Refined -> values += completed.value
                    is Refinement.Rejected -> return completed
                }
            }
            return Refinement.Refined(Unit)
        }

        private fun anonymous(
            function: org.jetbrains.kotlin.psi.KtFunction,
            item: Pending,
        ): Refinement<Unit, CallbackInvocationFlowCause> {
            val body =
                context.anonymous(function)
                    ?: return rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
            return retain(ImmutableCallbackValueOrigin.Anonymous(body), item)
        }

        private fun named(
            value: KtCallableReferenceExpression,
            item: Pending,
        ): Refinement<Unit, CallbackInvocationFlowCause> =
            when (val resolved = IntellijNamedCallbackReference(context, summaries).readOrigin(value)) {
                is Refinement.Refined -> retain(resolved.value, item)
                is Refinement.Rejected -> resolved
            }

        private fun retain(
            origin: ImmutableCallbackValueOrigin,
            item: Pending,
        ): Refinement<Unit, CallbackInvocationFlowCause> {
            val value =
                when (val complete = complete(origin, owner, item.arrivals)) {
                    is Refinement.Refined -> complete.value
                    is Refinement.Rejected -> return complete
                }
            return when (val retained = summaries.retention.admit(value.retainedBytes)) {
                is Refinement.Refined -> {
                    values += value
                    Refinement.Refined(Unit)
                }
                is Refinement.Rejected -> retained
            }
        }
    }

    private fun branches(
        expression: KtExpression,
        alternatives: List<KtExpression>,
        owner: RelationEndpoint,
        arrivals: List<Arrival>,
        visited: Set<PsiElement>,
        pending: ArrayDeque<Pending>,
    ): Refinement<Unit, CallbackInvocationFlowCause> {
        val destination =
            context.site(expression, owner, ValueRole.ExpressionResult)
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        for (alternative in alternatives) {
            when (val permitted = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return permitted
            }
            val result = if (alternative is KtBlockExpression) alternative.statements.lastOrNull() else alternative
            if (result == null) return rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            pending.add(
                Pending(result, listOf(Arrival(destination, ValueTransferKind.BRANCH_ALTERNATIVE)) + arrivals, visited)
            )
        }
        return Refinement.Refined(Unit)
    }

    private fun append(
        value: ImmutableCallbackValue,
        arrivals: List<Arrival>,
    ): Refinement<ImmutableCallbackValue, CallbackInvocationFlowCause> {
        var current = value
        for (arrival in arrivals) when (val result = transport(current, arrival.target, arrival.kind)) {
            is Refinement.Refined -> current = result.value
            is Refinement.Rejected -> return result
        }
        return Refinement.Refined(current)
    }

    private fun complete(
        origin: ImmutableCallbackValueOrigin,
        owner: RelationEndpoint,
        arrivals: List<Arrival>,
    ): Refinement<ImmutableCallbackValue, CallbackInvocationFlowCause> {
        val source =
            when (val admitted = ValueSite.fromCompiler(owner, origin.range, ValueRole.ExpressionResult)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        var current = source
        val transfers = mutableListOf<ValueTransfer>()
        for (arrival in arrivals) {
            when (val transfer = ValueTransfer.fromCompiler(current, arrival.target, arrival.kind)) {
                is Refinement.Refined -> transfers += transfer.value
                is Refinement.Rejected -> return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
            current = arrival.target
        }
        return when (val admitted = ImmutableCallbackValue.fromCompiler(origin, source, current, transfers)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}
