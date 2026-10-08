@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression

/** Request-local compiler refinement; scheduling, grants and transfer admission remain in the existing reader. */
internal object NativeTryBranchResult {
    fun admit(
        expression: KtExpression,
        position: TryBranchResultPosition.Result,
        observation: IntellijReadObservation,
    ): Refinement<ValueTransferEvidence.NormalBranchResult, ValueFlowUnsupportedCause> {
        observation.count(IntellijReadCounter.VALUE_FLOW_BRANCH_RESULT_CANDIDATES)
        when (val condition = completion(expression, position)) {
            Completion.VALUE -> Unit
            Completion.ABRUPT,
            Completion.LIMIT,
            Completion.UNAVAILABLE,
            Completion.FINALLY -> return rejectCompletion(condition, observation)
        }
        val tryRange =
            when (val range = range(position.enclosing)) {
                is Refinement.Refined -> range.value
                is Refinement.Rejected -> return range
            }
        val branchRange =
            when (val range = range(position.branch)) {
                is Refinement.Refined -> range.value
                is Refinement.Rejected -> return range
            }
        return when (
            val evidence =
                ValueTransferEvidence.NormalBranchResult.fromCompiler(tryRange, branchRange, position.alternative)
        ) {
            is Refinement.Refined -> evidence
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    }

    private fun completion(expression: KtExpression, position: TryBranchResultPosition.Result): Completion =
        when {
            position.enclosing.finallyBlock != null -> Completion.FINALLY
            expression.isAbruptExit() -> Completion.ABRUPT
            else ->
                analyze(position.enclosing) {
                    val sourceType = expression.expressionType
                    val targetType = position.enclosing.expressionType
                    if (sourceType == null || targetType == null) Completion.UNAVAILABLE
                    else checkedCompletion(sourceType, targetType)
                }
        }

    private fun KaSession.checkedCompletion(sourceType: KaType, targetType: KaType): Completion =
        when (nativeCompilerTypeProof(listOf(sourceType, targetType))) {
            NativeCompilerTypeProof.ADMITTED ->
                when (val source = completion(sourceType)) {
                    Completion.VALUE -> completion(targetType)
                    Completion.ABRUPT,
                    Completion.LIMIT,
                    Completion.UNAVAILABLE,
                    Completion.FINALLY -> source
                }
            NativeCompilerTypeProof.WORK_LIMIT_REACHED -> Completion.LIMIT
            NativeCompilerTypeProof.ERROR_TYPE,
            NativeCompilerTypeProof.UNSUPPORTED_TYPE -> Completion.UNAVAILABLE
        }

    private fun KtExpression.isAbruptExit(): Boolean =
        when (this) {
            is KtReturnExpression,
            is KtThrowExpression,
            is KtBreakExpression,
            is KtContinueExpression -> true
            else -> false
        }

    private fun rejectCompletion(
        condition: Completion,
        observation: IntellijReadObservation,
    ): Refinement.Rejected<ValueFlowUnsupportedCause> {
        val (counter, failure) =
            when (condition) {
                Completion.ABRUPT ->
                    IntellijReadCounter.VALUE_FLOW_ABRUPT_REJECTIONS to ValueFlowUnsupportedCause.ABRUPT_COMPLETION
                Completion.LIMIT ->
                    IntellijReadCounter.VALUE_FLOW_BRANCH_RESULT_TYPE_REJECTIONS to
                        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
                Completion.UNAVAILABLE ->
                    IntellijReadCounter.VALUE_FLOW_BRANCH_RESULT_TYPE_REJECTIONS to
                        ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION
                Completion.FINALLY ->
                    IntellijReadCounter.VALUE_FLOW_FINALLY_REJECTIONS to ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED
                Completion.VALUE -> error("An admitted normal result cannot be rejected")
            }
        observation.count(counter)
        return Refinement.Rejected(failure)
    }

    private enum class Completion {
        VALUE,
        ABRUPT,
        LIMIT,
        UNAVAILABLE,
        FINALLY,
    }

    private fun KaSession.completion(type: KaType): Completion =
        when (type) {
            is KaErrorType -> Completion.UNAVAILABLE
            is KaClassType ->
                when (type.classId.asSingleFqName().asString()) {
                    "kotlin.Unit" -> Completion.UNAVAILABLE
                    "kotlin.Nothing" -> if (type.isMarkedNullable) Completion.VALUE else Completion.ABRUPT
                    else -> Completion.VALUE
                }
            else -> Completion.VALUE
        }

    private fun range(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
        when (
            val parsed = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)
        ) {
            is Refinement.Refined -> parsed
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
}
