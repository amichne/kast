package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Qualified reads retain ordinary receiver flow unless the compiler proves a stored-field accessor. */
internal class NativeValueQualifiedReceiver(
    private val request: io.github.amichne.kast.relation.contract.ValueFlowRequest,
    private val allowance: IntellijRelationAllowance,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val retained: NativeValueFlowTransfers,
) {
    private val source: ValueSite = request.source

    fun read(expression: KtExpression, qualified: KtQualifiedExpression) {
        val call = qualified.selectorExpression as? KtCallElement
        if (call == null) {
            when (
                val result =
                    nativeValueFieldRead(
                        source = source,
                        expression = qualified,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> field(result.value)
                is Refinement.Rejected -> getter(expression, qualified, result.failure)
            }
            return
        }
        callReceiver(expression, qualified, call)
    }

    private fun getter(
        expression: KtExpression,
        qualified: KtQualifiedExpression,
        cause: io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause,
    ) {
        val syntax = qualified.nativeValueCallSyntax() as? NativeValueCallSyntax.PropertyRead
        if (
            syntax == null ||
                cause != io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY
        )
            retained.unresolvedAt(cause, qualified)
        else
            when (
                val getter =
                    nativeNamedGetterReceiver(
                        syntax = syntax,
                        execution = source.executionOwner,
                        scope = scope,
                        projection = projection,
                    )
            ) {
                is Refinement.Refined -> retained.emit(expression, getter.value, ValueTransferKind.RECEIVER)
                is Refinement.Rejected -> retained.unresolvedAt(getter.failure, qualified)
            }
    }

    private fun callReceiver(expression: KtExpression, qualified: KtQualifiedExpression, call: KtCallElement) {
        when (
            val generated =
                nativeGeneratedComponentRead(
                    source = source,
                    expression = expression,
                    call = call,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Rejected -> {
                retained.unresolvedAt(generated.failure, qualified)
                return
            }
            is Refinement.Refined ->
                when (val result = generated.value) {
                    NativeGeneratedComponentRead.NotGenerated -> Unit
                    is NativeGeneratedComponentRead.Field -> {
                        field(result.read)
                        return
                    }
                }
        }
        when (
            val role =
                nativeValueReceiver(
                    expression = expression,
                    qualified = qualified,
                    scope = scope,
                    projection = projection,
                    executionOwner = source.executionOwner,
                )
        ) {
            is Refinement.Refined -> retained.emit(expression, role.value, ValueTransferKind.RECEIVER)
            is Refinement.Rejected ->
                if (role.failure == io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.EXTERNAL_CALL) {
                    when (val reviewed = nativeReviewedReceiverRole(request, qualified, scope, projection, allowance)) {
                        is Refinement.Refined -> retained.emit(expression, reviewed.value, ValueTransferKind.RECEIVER)
                        is Refinement.Rejected -> retained.unresolvedAt(reviewed.failure, qualified)
                    }
                } else retained.unresolvedAt(role.failure, qualified)
        }
    }

    private fun field(read: NativeValueFieldRead) =
        when (read) {
            is NativeValueFieldRead.Transfer ->
                retained.emitTarget(read.value.target, read.value.kind, read.value.evidence)
            NativeValueFieldRead.DifferentField -> Unit
        }
}
