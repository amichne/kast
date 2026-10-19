package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Bounded native proof attached to callback observations without changing named-call ownership. */
internal fun readCallbackInvocationFlow(
    boundary: PsiElement,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    admitWork: () -> CallbackWorkAdmission,
    summaries: CallbackParameterSummaries,
    callbackExpiry: () -> CallbackExpiryAdmission,
): CallbackInvocationFlowRead {
    val context =
        IntellijCallbackFlowContext(
            scope = scope,
            projection = projection,
            admitWork = admitWork,
            observation = summaries.observation,
            callbackExpiry = callbackExpiry,
        )
    when (val allowed = context.permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(allowed.failure)
    }
    val literal =
        boundary as? KtFunction
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    val body =
        context.anonymous(literal)
            ?: return CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return NativeCallbackInvocationFlow(
            context = context,
            lexicalOwner = lexicalOwner,
            summaries = summaries,
            literal = literal,
            body = body,
        )
        .read()
}

internal class NativeCallbackInvocationFlow(
    private val context: IntellijCallbackFlowContext,
    private val lexicalOwner: CompilerGroundedSymbolEvidence,
    private val summaries: CallbackParameterSummaries,
    private val literal: KtFunction,
    private val body: RelationCallableBody.Anonymous,
) {
    fun read(): CallbackInvocationFlowRead {
        val prepared = IntellijCallbackBindingReader(context, lexicalOwner).prepare(literal)
        val binding =
            when (prepared) {
                is CallbackBindingPreparation.Prepared -> prepared.value.binding
                is CallbackBindingPreparation.Direct -> CallbackBindingEvidence.Direct(prepared.binding)
                is CallbackBindingPreparation.DeferredDependency ->
                    CallbackBindingEvidence.DeferredDependency(prepared.binding)
                is CallbackBindingPreparation.DependencyContract ->
                    CallbackBindingEvidence.DependencyContract(prepared.binding)
                is CallbackBindingPreparation.Unavailable -> CallbackBindingEvidence.Unavailable(prepared.cause)
                is CallbackBindingPreparation.ContractRejected ->
                    return CallbackInvocationFlowRead.ContractRejected(prepared.cause)
            }
        return summaries.bodyReads.read(
            body = body,
            binding = binding,
            basis = context.scope.request.subject.lease.identity,
        ) {
            readPrepared(prepared)
        }
    }

    private fun readPrepared(prepared: CallbackBindingPreparation): CallbackInvocationFlowRead =
        when (prepared) {
            is CallbackBindingPreparation.Unavailable -> unavailable(prepared.cause)
            is CallbackBindingPreparation.ContractRejected ->
                CallbackInvocationFlowRead.ContractRejected(prepared.cause)
            is CallbackBindingPreparation.Direct ->
                readDirectCallbackFlow(
                    context = context,
                    prepared = prepared,
                    body = body,
                    lexicalOwner = lexicalOwner,
                    summaries = summaries,
                )
            is CallbackBindingPreparation.DeferredDependency ->
                context.observed(
                    body = body,
                    binding =
                        io.github.amichne.kast.relation.contract.CallbackBindingEvidence.DeferredDependency(
                            prepared.binding
                        ),
                    invocations = emptyList(),
                    obligations = emptySet(),
                    scan = io.github.amichne.kast.relation.contract.CallbackInvocationScan.EXHAUSTIVE,
                )
            is CallbackBindingPreparation.DependencyContract -> dependency(prepared.binding)
            is CallbackBindingPreparation.Prepared -> prepared(prepared.value)
        }

    private fun dependency(
        binding: io.github.amichne.kast.relation.contract.CallbackDependencyContract
    ): CallbackInvocationFlowRead {
        val owner = binding.owner
        val obligations =
            if (owner is RelationCallableBody.Anonymous) setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
            else emptySet()
        fun observed() =
            context.observed(
                body = body,
                binding = CallbackBindingEvidence.DependencyContract(binding),
                invocations = emptyList(),
                obligations = obligations,
                scan = io.github.amichne.kast.relation.contract.CallbackInvocationScan.EXHAUSTIVE,
            )
        if (owner !is RelationCallableBody.Anonymous) return observed()
        val ownerLiteral =
            (literal.parent.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
                ?: return CallbackInvocationFlowRead.Unavailable(
                    CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
                )
        val enclosing =
            context.endpoint(lexicalOwner)
                ?: return CallbackInvocationFlowRead.Unavailable(
                    CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
                )
        val ownerBinding =
            when (
                val read = IntellijCallbackOwnerBindingReader(context, summaries).read(ownerLiteral, owner, enclosing)
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(read.failure)
            }
        when (val allowed = summaries.retention.admitOwner(ownerBinding)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(allowed.failure)
        }
        return when (val read = observed()) {
            is CallbackInvocationFlowRead.Observed ->
                when (val admitted = read.flow.withOwnerBindings(listOf(ownerBinding))) {
                    is Refinement.Refined -> CallbackInvocationFlowRead.Observed(admitted.value)
                    is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(admitted.failure)
                }
            is CallbackInvocationFlowRead.Immutable,
            is CallbackInvocationFlowRead.Unavailable,
            is CallbackInvocationFlowRead.ContractRejected -> read
        }
    }

    private fun unavailable(cause: CallbackInvocationFlowCause): CallbackInvocationFlowRead {
        if (
            cause !in
                setOf(
                    CallbackInvocationFlowCause.STORED_CALLBACK,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    CallbackInvocationFlowCause.RETURNED_CALLBACK,
                )
        )
            return context.unavailableCallbackSupply(
                literal = literal,
                body = body,
                lexicalOwner = lexicalOwner,
                cause = cause,
                summaries = summaries,
            )
        val expression =
            expression()
                ?: return context.unavailableCallbackSupply(
                    literal = literal,
                    body = body,
                    lexicalOwner = lexicalOwner,
                    cause = cause,
                    summaries = summaries,
                )
        return immutable(expression)
    }

    private fun prepared(value: PreparedCallbackFlow): CallbackInvocationFlowRead {
        if (value.origin is PreparedCallbackOrigin.Default) {
            return when (
                val read =
                    IntellijImmutableCallbackFlowReader(context, summaries)
                        .readDefault(value, ImmutableCallbackValueOrigin.Anonymous(body))
            ) {
                is Refinement.Refined -> CallbackInvocationFlowRead.Immutable(read.value)
                is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(read.failure)
            }
        }
        if (!returnsCallable(value.function, context.observation))
            return IntellijCallbackFlowRead(context = context, prepared = value, body = body, summaries = summaries)
                .read()
        val expression =
            expression()
                ?: return CallbackInvocationFlowRead.Unavailable(
                    CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
                )
        return immutable(expression)
    }

    private fun immutable(expression: KtExpression): CallbackInvocationFlowRead =
        when (
            val read =
                IntellijImmutableCallbackFlowReader(context, summaries)
                    .read(expression, lexicalOwner, ImmutableCallbackValueOrigin.Anonymous(body))
        ) {
            is Refinement.Refined -> CallbackInvocationFlowRead.Immutable(read.value)
            is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(read.failure)
        }

    private fun expression(): KtExpression? =
        if (literal is KtFunctionLiteral) literal.parent as? KtLambdaExpression else literal as? KtExpression
}
