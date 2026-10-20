@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal sealed interface NativeReviewedCallbackFailure {
    data class Model(val cause: NativeValueModelFailure) : NativeReviewedCallbackFailure

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeReviewedCallbackFailure

    data object Stale : NativeReviewedCallbackFailure
}

/** Entry and return reacquire the same source literal through one current reviewed call and the original allowance. */
internal class NativeValueReviewedCallbacks(
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun frame(expected: ValueInvocation): Refinement<ValueCallFrame, NativeReviewedCallbackFailure> {
        val restored =
            when (val value = restore(expected)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val binding =
            when (
                val value =
                    NativeValueReviewedCall(
                            caller = restored.caller,
                            scope = scope,
                            projection = projection,
                            permit = permit,
                        )
                        .bind(restored.qualified.receiverExpression, restored.qualified, ReviewedValueModel.Let)
            ) {
                is Refinement.Refined -> value.value.binding
                is Refinement.Rejected -> return Refinement.Rejected(NativeReviewedCallbackFailure.Model(value.failure))
            }
        if (!binding.matchesInvocation(expected)) return staleCallback()
        val supplied =
            when (val value = supply(restored.qualified)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val formal =
            when (val value = formal(expected, supplied.functionLiteral)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val argument = RelationOccurrence.fromSource(expected.enclosing.file, sourceValueRange(supplied))
        return when (val value = ValueCallFrame.reviewedCallback(binding, argument, formal)) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> unsupportedCallback(ValueFlowUnsupportedCause.UNMODELED_CALL)
        }
    }

    private fun restore(
        expected: ValueInvocation
    ): Refinement<NativeReviewedCallbackCall, NativeReviewedCallbackFailure> {
        if (
            expected.signature != ReviewedValueModel.Let.callable ||
                expected.basis != scope.request.subject.lease.identity
        )
            return staleCallback()
        when (val allowed = admit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val host =
            when (val value = projection.subject(scope, expected.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return staleCallback()
            }
        val qualified =
            host.declaration.exactValueElement(expected.range) as? KtQualifiedExpression ?: return staleCallback()
        val call = qualified.selectorExpression as? KtCallExpression ?: return staleCallback()
        if (
            call.calleeExpression?.text !=
                ReviewedValueModel.Let.callable.qualifiedIdentity.value.substringAfterLast('.')
        )
            return staleCallback()
        return caller(expected, host.declaration, qualified)
    }

    private fun caller(
        expected: ValueInvocation,
        host: PsiElement,
        qualified: KtQualifiedExpression,
    ): Refinement<NativeReviewedCallbackCall, NativeReviewedCallbackFailure> {
        when (val allowed = sourceSite(qualified)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (val allowed = admit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val caller =
            when (
                val value =
                    nativeValueExecutionOwner(
                        element = qualified,
                        host = host,
                        enclosing = expected.enclosing,
                        scope = scope,
                        observation = projection.observation,
                    )
            ) {
                is Refinement.Refined -> value.value.owner
                is Refinement.Rejected -> return staleCallback()
            }
        if (
            caller != expected.executionOwner ||
                caller.enclosing != expected.executionOwner.enclosing ||
                caller.body != expected.executionOwner.body
        )
            return staleCallback()
        return Refinement.Refined(NativeReviewedCallbackCall(qualified, caller))
    }

    private fun supply(
        qualified: KtQualifiedExpression
    ): Refinement<KtLambdaExpression, NativeReviewedCallbackFailure> {
        val call = qualified.selectorExpression as? KtCallExpression ?: return staleCallback()
        when (val allowed = admit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        return projection.observation.observedAnalyze(call) {
            val resolved = call.resolveCall() ?: return@observedAnalyze staleCallback()
            val parameter =
                resolved.signature.symbol.valueParameters.singleOrNull()
                    ?: return@observedAnalyze unsupportedCallback(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
            val supplied =
                resolved.valueArgumentMapping.entries.singleOrNull { it.value.symbol == parameter }?.key
                    as? KtLambdaExpression
                    ?: return@observedAnalyze unsupportedCallback(ValueFlowUnsupportedCause.UNMODELED_CALL)
            val symbol = supplied.functionLiteral.symbol
            if (
                symbol.origin != KaSymbolOrigin.SOURCE ||
                    symbol.receiverParameter != null ||
                    symbol.valueParameters.size != 1
            )
                return@observedAnalyze unsupportedCallback(ValueFlowUnsupportedCause.UNMODELED_CALL)
            Refinement.Refined(supplied)
        }
    }

    private fun formal(
        expected: ValueInvocation,
        literal: KtFunctionLiteral,
    ): Refinement<ValueFormalParameter, NativeReviewedCallbackFailure> {
        val execution =
            when (val value = execution(expected, literal)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        when (val allowed = admit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val position =
            when (val value = ValueArgumentPosition.parse(0)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return unsupportedCallback(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
            }
        return when (
            val value =
                nativeCallbackFormal(
                    literal = literal,
                    executionOwner = execution,
                    argument = position,
                    observation = projection.observation,
                )
        ) {
            is Refinement.Refined ->
                when (val actual = value.value) {
                    is ValueFormalPosition.Parameter -> Refinement.Refined(actual.parameter)
                    is ValueFormalPosition.Receiver -> unsupportedCallback(ValueFlowUnsupportedCause.UNMODELED_CALL)
                }
            is Refinement.Rejected -> unsupportedCallback(value.failure)
        }
    }

    private fun execution(
        expected: ValueInvocation,
        literal: KtFunctionLiteral,
    ): Refinement<ValueExecutionOwner, NativeReviewedCallbackFailure> {
        when (val allowed = sourceSite(literal)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (val allowed = admit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val body =
            nativeAnonymousCallableBody(literal, scope, projection.observation)
                ?: return unsupportedCallback(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        return when (val value = ValueExecutionOwner.fromCompiler(expected.enclosing, body, expected.basis)) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> unsupportedCallback(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    }

    private fun sourceSite(
        element: org.jetbrains.kotlin.psi.KtElement
    ): Refinement<Unit, NativeReviewedCallbackFailure> =
        when (scope.admitProviderSite(element.containingFile?.virtualFile)) {
            RelationProviderScopeAdmission.ADMITTED -> Refinement.Refined(Unit)
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED ->
                unsupportedCallback(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED ->
                unsupportedCallback(ValueFlowUnsupportedCause.EXTERNAL_CALL)
            RelationProviderScopeAdmission.UNAVAILABLE ->
                unsupportedCallback(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }

    private fun admit(): Refinement<Unit, NativeReviewedCallbackFailure> =
        when (val value = permit()) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> unsupportedCallback(value.failure)
        }
}

private data class NativeReviewedCallbackCall(val qualified: KtQualifiedExpression, val caller: ValueExecutionOwner)

private fun <T> staleCallback(): Refinement<T, NativeReviewedCallbackFailure> =
    Refinement.Rejected(NativeReviewedCallbackFailure.Stale)

private fun <T> unsupportedCallback(cause: ValueFlowUnsupportedCause): Refinement<T, NativeReviewedCallbackFailure> =
    Refinement.Rejected(NativeReviewedCallbackFailure.Unsupported(cause))
