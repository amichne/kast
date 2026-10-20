@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.psi.KtExpression

internal fun nativeBranchCarriesValue(
    expression: KtExpression,
    branch: KtExpression,
    observation: IntellijReadObservation,
): Refinement<Unit, ValueFlowUnsupportedCause> =
    observation.observedAnalyze(branch) {
        val source = expression.expressionType ?: return@observedAnalyze unsupportedBranch()
        val target = branch.expressionType ?: return@observedAnalyze unsupportedBranch()
        normalBranchValueTypes(source, target)
    }

internal fun KaSession.normalBranchValueTypes(
    source: KaType,
    target: KaType,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    when (nativeCompilerTypeProof(listOf(source, target))) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return unsupportedBranch()
    }
    return when (val normal = branchValueType(source)) {
        is Refinement.Refined -> branchValueType(target)
        is Refinement.Rejected -> normal
    }
}

private fun KaSession.branchValueType(type: KaType): Refinement<Unit, ValueFlowUnsupportedCause> =
    when (type) {
        is KaClassType ->
            when (type.classId.asSingleFqName().asString()) {
                "kotlin.Unit" -> unsupportedBranch()
                "kotlin.Nothing" ->
                    if (type.isMarkedNullable) Refinement.Refined(Unit)
                    else Refinement.Rejected(ValueFlowUnsupportedCause.ABRUPT_COMPLETION)
                else -> Refinement.Refined(Unit)
            }
        is KaTypeParameterType -> Refinement.Refined(Unit)
        else -> unsupportedBranch()
    }

private fun unsupportedBranch() = Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
