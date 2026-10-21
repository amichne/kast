@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName

/** Enumerates one source body; the existing flow coordinator owns every allowance and emitted transfer. */
internal class NativeValueReceiverReads(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
) {
    fun forEachRead(
        function: KtNamedFunction,
        admitTraversal: () -> Boolean,
        permit: () -> Boolean,
        consume: (Refinement<ValueTransfer, ValueFlowUnsupportedCause>) -> Unit,
    ): Boolean {
        val body = function.bodyExpression ?: return false
        return PsiTreeUtil.processElements(body) { element ->
            admitTraversal() && readElement(function, element, permit, consume)
        }
    }

    private fun readElement(
        function: KtNamedFunction,
        element: PsiElement,
        permit: () -> Boolean,
        consume: (Refinement<ValueTransfer, ValueFlowUnsupportedCause>) -> Unit,
    ): Boolean {
        if (element is KtThisExpression) return thisRead(function, element, permit, consume)
        if (element is KtCallExpression) return permit() && calls(function, element, permit, consume)
        val name = element as? KtNameReferenceExpression ?: return true
        if (!name.isFieldReceiverCandidate()) return true
        if (!permit()) return false
        readField(function, name, consume)
        return true
    }

    private fun readField(
        function: KtNamedFunction,
        name: KtNameReferenceExpression,
        consume: (Refinement<ValueTransfer, ValueFlowUnsupportedCause>) -> Unit,
    ) {
        when (val field = receiverField(function, name)) {
            NativeReceiverField.Unrelated -> Unit
            is NativeReceiverField.Selected -> {
                when (val projected = projected(function, field)) {
                    is Refinement.Refined ->
                        when (val read = projected.value) {
                            is NativeValueFieldRead.Transfer -> consume(Refinement.Refined(read.value))
                            NativeValueFieldRead.DifferentField -> Unit
                        }
                    is Refinement.Rejected -> consume(projected)
                }
            }
            is NativeReceiverField.Unsupported -> consume(Refinement.Rejected(field.cause))
        }
    }

    private fun thisRead(
        function: KtNamedFunction,
        expression: KtThisExpression,
        permit: () -> Boolean,
        consume: (Refinement<ValueTransfer, ValueFlowUnsupportedCause>) -> Unit,
    ): Boolean {
        if (valueNativeOwnership(expression, function) != NativeValueOwnership.ADMITTED) {
            consume(Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION))
            return true
        }
        if (!scope.nativeScope.contains(expression.containingFile.virtualFile)) {
            consume(Refinement.Rejected(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN))
            return true
        }
        if (!permit()) return false
        when (val actual = nativeThisReceiverKind(function, expression, projection.observation)) {
            is Refinement.Refined -> {
                val position = (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver
                if (position?.kind == actual.value) consume(nativeThisRead(source, expression))
            }
            is Refinement.Rejected -> consume(actual)
        }
        return true
    }

    private fun calls(
        function: KtNamedFunction,
        call: KtCallExpression,
        permit: () -> Boolean,
        consume: (Refinement<ValueTransfer, ValueFlowUnsupportedCause>) -> Unit,
    ): Boolean {
        val read = NativeValueImplicitReceiverCalls(source, scope, projection).read(function, call)
        for (cause in read.causes) consume(Refinement.Rejected(cause))
        for ((index, receiver) in read.receivers.withIndex()) {
            if (index > 0 && !permit()) return false
            consume(nativeImplicitReceiverArrival(source, receiver))
        }
        return true
    }

    private fun projected(
        function: KtNamedFunction,
        candidate: NativeReceiverField.Selected,
    ): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> {
        if (valueNativeOwnership(candidate.expression, function) != NativeValueOwnership.ADMITTED)
            return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        val field =
            when (
                val value =
                    nativeValueField(
                        declaration = candidate.declaration,
                        enclosing = source.enclosing,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        return sourceFieldTarget(source, candidate.expression, field)
    }

    private fun receiverField(function: KtNamedFunction, expression: KtNameReferenceExpression): NativeReceiverField =
        projection.observation.observedAnalyze(expression) {
            val position =
                (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver
                    ?: return@observedAnalyze NativeReceiverField.Unsupported(ValueFlowUnsupportedCause.UNMODELED_CALL)
            val call = expression.resolveCall() ?: return@observedAnalyze missingReceiverField(expression)
            val property =
                call.signature.symbol as? KaKotlinPropertySymbol ?: return@observedAnalyze NativeReceiverField.Unrelated
            when (val proof = receiverFieldGuard(function, position, expression, call.dispatchReceiver)) {
                NativeReceiverFieldProof.Unrelated -> return@observedAnalyze NativeReceiverField.Unrelated
                is NativeReceiverFieldProof.Unsupported ->
                    return@observedAnalyze NativeReceiverField.Unsupported(proof.cause)
                NativeReceiverFieldProof.Matched -> Unit
            }
            val declaration =
                property.psi as? PsiNamedElement
                    ?: return@observedAnalyze NativeReceiverField.Unsupported(
                        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
                    )
            val parent = expression.parent as? KtQualifiedExpression
            NativeReceiverField.Selected(
                declaration,
                if (parent?.selectorExpression === expression) parent else expression,
            )
        }
}

private fun KaSession.missingReceiverField(expression: KtNameReferenceExpression): NativeReceiverField =
    when (expression.references.filterIsInstance<KtReference>().singleOrNull()?.resolveToSymbol()) {
        is KaKotlinPropertySymbol,
        null -> NativeReceiverField.Unsupported(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        else -> NativeReceiverField.Unrelated
    }

/** The current named execution and its actual K2 receiver symbol establish the alias, including labeled this. */
internal fun nativeThisReceiverKind(
    function: KtNamedFunction,
    expression: KtThisExpression,
    observation: IntellijReadObservation,
): Refinement<ValueReceiverKind, ValueFlowUnsupportedCause> =
    observation.observedAnalyze(expression) {
        val reference =
            expression.instanceReference.references.filterIsInstance<KtReference>().singleOrNull()
                ?: return@observedAnalyze Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        val actual =
            reference.resolveToSymbol()
                ?: return@observedAnalyze Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        if (actual.origin != KaSymbolOrigin.SOURCE)
            return@observedAnalyze Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        val callable = function.symbol
        when (actual) {
            callable.receiverParameter -> Refinement.Refined(ValueReceiverKind.EXTENSION)
            callable.containingDeclaration -> Refinement.Refined(ValueReceiverKind.DISPATCH)
            else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

private fun nativeThisRead(
    source: ValueSite,
    expression: KtThisExpression,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val target =
        when (
            val admitted =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = sourceValueRange(expression),
                    role = ValueRole.LocalRead,
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    return when (val admitted = ValueTransfer.fromCompiler(source, target, ValueTransferKind.LOCAL_READ)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
}

private fun KtNameReferenceExpression.isFieldReceiverCandidate(): Boolean =
    parent !is KtValueArgumentName && parent !is KtUserType && (parent as? KtCallElement)?.calleeExpression !== this

private sealed interface NativeReceiverField {
    data class Selected(
        val declaration: PsiNamedElement,
        val expression: KtExpression,
    ) : NativeReceiverField

    data object Unrelated : NativeReceiverField

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeReceiverField
}
