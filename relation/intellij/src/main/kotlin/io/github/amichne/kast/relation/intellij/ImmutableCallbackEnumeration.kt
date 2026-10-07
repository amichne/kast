package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Per-source bounded queue, terminal uses, and typed incomplete obligations. */
internal data class ImmutableCallbackPending(
    val expression: KtExpression,
    val value: ImmutableCallbackValue,
    val visited: Set<PsiElement>,
)

internal class ImmutableCallbackEnumeration(
    val context: IntellijCallbackFlowContext,
    val summaries: CallbackParameterSummaries,
    val origin: ImmutableCallbackValueOrigin,
    val source: ValueSite,
) {
    val pending = ArrayDeque<ImmutableCallbackPending>()
    val uses = mutableListOf<ImmutableCallbackInvocationUse>()
    val obligations = linkedSetOf<CallbackInvocationFlowCause>()
    var stopped = false

    fun retain(use: ImmutableCallbackInvocationUse) {
        when (val retained = summaries.retention.admit(use.value.retainedBytes)) {
            is Refinement.Refined -> uses += use
            is Refinement.Rejected -> {
                obligations += retained.failure
                stopped = true
            }
        }
    }

    fun schedule(item: ImmutableCallbackPending, next: KtExpression, target: ValueSite, kind: ValueTransferKind) {
        when (val transported = transport(item.value, target, kind)) {
            is Refinement.Refined ->
                when (val retained = summaries.retention.admit(transported.value.retainedBytes)) {
                    is Refinement.Refined ->
                        pending.add(ImmutableCallbackPending(next, transported.value, item.visited + item.expression))
                    is Refinement.Rejected -> {
                        obligations += retained.failure
                        stopped = true
                    }
                }
            is Refinement.Rejected -> obligations += transported.failure
        }
    }

    fun read(
        expression: KtExpression,
        initial: ImmutableCallbackValue,
    ): Refinement<ImmutableCallbackInvocationFlow, CallbackInvocationFlowFailure> {
        pending.add(ImmutableCallbackPending(expression, initial, emptySet()))
        while (pending.isNotEmpty() && !stopped) {
            when (val permitted = context.permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> {
                    obligations += permitted.failure
                    break
                }
            }
            when (val result = visit(pending.removeFirst())) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return result
            }
        }
        return complete()
    }

    private fun visit(item: ImmutableCallbackPending): Refinement<Unit, CallbackInvocationFlowFailure> {
        if (item.expression in item.visited) return obligation(CallbackInvocationFlowCause.CALLBACK_CYCLE)
        val endpoint =
            item.value.destination.enclosing as? RelationEndpoint.Resolved
                ?: return obligation(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val owner =
            when (val declaration = item.expression.nearestDeclaration()) {
                is ContainingDeclaration.Deferred -> declaration.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> declaration
            }
                as? ContainingDeclaration.Found
                ?: return obligation(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        var value = item.expression
        while (value.parent is KtParenthesizedExpression || value.parent is KtLabeledExpression) value =
            value.parent as KtExpression
        return visitParent(item, value, endpoint, owner)
    }

    fun obligation(cause: CallbackInvocationFlowCause): Refinement<Unit, CallbackInvocationFlowFailure> {
        obligations += cause
        return Refinement.Refined(Unit)
    }

    private fun complete(): Refinement<ImmutableCallbackInvocationFlow, CallbackInvocationFlowFailure> {
        val flow =
            ImmutableCallbackInvocationFlow.fromCompiler(
                origin,
                source,
                uses.distinct(),
                obligations,
                if (obligations.isEmpty()) CallbackInvocationScan.EXHAUSTIVE else CallbackInvocationScan.INCOMPLETE,
            )
        return when (flow) {
            is Refinement.Refined -> {
                flow.value.uses.filterIsInstance<ImmutableCallbackInvocationUse.Supplied>().forEach {
                    summaries.used(it.summary)
                }
                flow
            }
            is Refinement.Rejected -> Refinement.Rejected(flow.failure.callbackFailure())
        }
    }
}

internal fun ImmutableCallbackEnumeration.visitParent(
    item: ImmutableCallbackPending,
    value: KtExpression,
    endpoint: RelationEndpoint.Resolved,
    owner: ContainingDeclaration.Found,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    when (val parent = value.parent) {
        is KtProperty -> property(item, value, parent, endpoint, owner)
        is KtValueArgument,
        is KtCallExpression,
        is KtQualifiedExpression -> binding(item, value, endpoint)
        is KtContainerNodeForControlStructureBody -> ifBranch(item, value, parent, endpoint)
        is KtWhenEntry -> whenBranch(item, value, parent, endpoint)
        is KtBlockExpression -> block(item, value, parent)
        is KtReturnExpression,
        is KtNamedFunction -> returned(item, owner)
        is KtParameter -> default(item, value, endpoint)
        else -> obligation(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
    }

internal fun ImmutableCallbackEnumeration.ifBranch(
    item: ImmutableCallbackPending,
    value: KtExpression,
    parent: KtContainerNodeForControlStructureBody,
    endpoint: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val branch = parent.parent as? KtIfExpression
    val site = branch?.let { context.site(it, endpoint, ValueRole.ExpressionResult) }
    if (branch == null || branch.condition === value || site == null)
        obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
    else schedule(item, branch, site, ValueTransferKind.BRANCH_ALTERNATIVE)

    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.whenBranch(
    item: ImmutableCallbackPending,
    value: KtExpression,
    parent: KtWhenEntry,
    endpoint: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val branch = parent.parent as? KtWhenExpression
    val site = branch?.let { context.site(it, endpoint, ValueRole.ExpressionResult) }
    if (branch == null || parent.expression !== value || site == null)
        obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
    else schedule(item, branch, site, ValueTransferKind.BRANCH_ALTERNATIVE)

    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.block(
    item: ImmutableCallbackPending,
    value: KtExpression,
    parent: KtBlockExpression,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (
        parent.statements.lastOrNull() === value &&
            (parent.parent is KtContainerNodeForControlStructureBody || parent.parent is KtWhenEntry)
    )
        pending.addFirst(item.copy(expression = parent, visited = item.visited + item.expression))
    else retain(ImmutableCallbackInvocationUse.Unused(item.value))

    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.default(
    item: ImmutableCallbackPending,
    value: KtExpression,
    endpoint: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    when (val binding = IntellijCallbackBindingReader(context, endpoint.evidence).prepareExpression(value)) {
        is CallbackBindingPreparation.Prepared -> {
            if (binding.value.origin !is PreparedCallbackOrigin.Default) {
                obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
                return Refinement.Refined(Unit)
            }
            when (
                val flow =
                    IntellijImmutableCallbackFlowReader(context, summaries)
                        .readDefault(binding.value, item.value.origin, item.value)
            ) {
                is Refinement.Refined -> {
                    flow.value.uses.forEach(::retain)
                    obligations += flow.value.obligations
                }
                is Refinement.Rejected -> return flow
            }
        }
        is CallbackBindingPreparation.Unavailable -> obligations += binding.cause
        is CallbackBindingPreparation.ContractRejected -> return Refinement.Rejected(binding.cause)
        is CallbackBindingPreparation.Direct -> obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
    }

    return Refinement.Refined(Unit)
}
