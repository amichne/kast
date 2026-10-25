package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Capture admission reuses callback composition; it never changes ordinary lexical ownership. */
internal fun nativeValueCaptureOwner(
    element: PsiElement,
    host: PsiElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    admitWork: () -> CallbackWorkAdmission,
    expiry: () -> CallbackExpiryAdmission,
): Refinement<NativeValueExecution, ValueFlowUnsupportedCause> {
    val anonymous =
        when (val result = captureLiteral(element, host)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val declaration = host as? PsiNamedElement ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val evidence =
        when (val result = projection.project(declaration)) {
            is IntellijRelationDeclarationProjection.Projected -> result.evidence
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    val read =
        readCallbackInvocationFlow(
            boundary = anonymous,
            lexicalOwner = evidence,
            scope = scope,
            projection = projection,
            admitWork = admitWork,
            summaries = CallbackParameterSummaries(scope.request.budget, projection.observation),
            callbackExpiry = expiry,
        )
    return when (val graph = captureGraph(read, evidence)) {
        is Refinement.Rejected -> {
            if (graph.failure != ValueFlowUnsupportedCause.NESTED_EXECUTION) return graph
            val context = IntellijCallbackFlowContext(scope, projection, admitWork, projection.observation, expiry)
            when (val reviewed = readReviewedCallbackValueBinding(anonymous, enclosing, context)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        NativeValueExecution(anonymous, ValueExecutionOwner.reviewedCallback(reviewed.value))
                    )
                is Refinement.Rejected -> Refinement.Rejected(reviewed.failure.valueFlowCause())
            }
        }
        is Refinement.Refined ->
            when (val owner = ValueExecutionOwner.callback(enclosing, graph.value.first, graph.value.second)) {
                is Refinement.Refined -> Refinement.Refined(NativeValueExecution(anonymous, owner.value))
                is Refinement.Rejected -> owner
            }
    }
}

private fun captureLiteral(element: PsiElement, host: PsiElement): Refinement<KtFunction, ValueFlowUnsupportedCause> {
    var current: PsiElement? = element
    var literal: KtFunction? = null
    while (current != null && current !== host) {
        when (current) {
            is KtFunctionLiteral -> literal = literal ?: current
            is KtNamedFunction -> {
                if (current.name != null) return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                literal = literal ?: current
            }
            is KtClassOrObject -> return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
        current = current.parent
    }
    val anonymous = literal
    if (current !== host || anonymous == null) return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    return Refinement.Refined(anonymous)
}

private fun captureGraph(
    read: CallbackInvocationFlowRead,
    evidence: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
): Refinement<Pair<RelationCallableBody.Anonymous, CompleteCallbackFlowGraph>, ValueFlowUnsupportedCause> {
    val body: RelationCallableBody.Anonymous
    val graph =
        when (read) {
            is CallbackInvocationFlowRead.Observed -> {
                body = read.flow.body
                CompleteCallbackFlowGraph.admit(read.flow)
            }
            is CallbackInvocationFlowRead.Immutable -> {
                val origin =
                    read.flow.origin as? io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin.Anonymous
                        ?: return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                body = origin.body
                val named =
                    when (val result = RelationCallableBody.Named.fromCompiler(evidence)) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                    }
                CompleteCallbackFlowGraph.admit(read.flow, named)
            }
            is CallbackInvocationFlowRead.Unavailable -> return Refinement.Rejected(read.cause.captureCause())
            is CallbackInvocationFlowRead.ContractRejected ->
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    return when (graph) {
        is Refinement.Rejected ->
            Refinement.Rejected(
                when (val cause = graph.failure) {
                    is io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure.Unavailable ->
                        cause.cause.captureCause()
                    else -> ValueFlowUnsupportedCause.NESTED_EXECUTION
                }
            )
        is Refinement.Refined -> Refinement.Refined(body to graph.value)
    }
}

private fun io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause.captureCause():
    ValueFlowUnsupportedCause =
    when (this) {
        io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause.WORK_LIMIT_REACHED ->
            ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause.TIME_LIMIT_REACHED ->
            ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause.RESULT_LIMIT_REACHED ->
            ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause.BYTE_LIMIT_REACHED ->
            ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        else -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }
