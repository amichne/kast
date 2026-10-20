@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInterpolationEntryIndex
import io.github.amichne.kast.relation.contract.ValueInterpolationScalarKind
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Only an immediate, compiler-proved scalar entry supplies the template's derived value. */
internal fun nativeStringInterpolation(
    expression: KtExpression,
    entry: KtStringTemplateEntryWithExpression,
    observation: IntellijReadObservation,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueTransferEvidence.StringInterpolation, ValueFlowUnsupportedCause> {
    if (entry.expression !== expression) return unsupportedInterpolation()
    val template = entry.parent as? KtStringTemplateExpression ?: return unsupportedInterpolation()
    val index =
        when (val found = interpolationIndex(template, entry, permit)) {
            is Refinement.Refined -> found.value
            is Refinement.Rejected -> return found
        }
    when (val allowed = permit()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return allowed
    }
    return observation.observedAnalyze(template) {
        val source = expression.expressionType ?: return@observedAnalyze unsupportedInterpolation()
        val target = template.expressionType ?: return@observedAnalyze unsupportedInterpolation()
        val kind =
            when (val admitted = interpolationScalar(source, target)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return@observedAnalyze admitted
            }
        when (
            val admitted =
                ValueTransferEvidence.StringInterpolation.fromCompiler(
                    templateRange = sourceValueRange(template),
                    entryRange = sourceValueRange(entry),
                    entryIndex = index,
                    scalarKind = kind,
                )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> unsupportedInterpolation()
        }
    }
}

private fun interpolationIndex(
    template: KtStringTemplateExpression,
    selected: KtStringTemplateEntryWithExpression,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueInterpolationEntryIndex, ValueFlowUnsupportedCause> {
    for ((index, entry) in template.entries.withIndex()) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        if (entry === selected)
            return when (val parsed = ValueInterpolationEntryIndex.parse(index)) {
                is Refinement.Refined -> parsed
                is Refinement.Rejected -> unsupportedInterpolation()
            }
    }
    return unsupportedInterpolation()
}

private fun KaSession.interpolationScalar(
    source: KaType,
    target: KaType,
): Refinement<ValueInterpolationScalarKind, ValueFlowUnsupportedCause> {
    when (nativeCompilerTypeProof(listOf(source, target))) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return unsupportedInterpolation()
    }
    if (source !is KaClassType || target !is KaClassType) return unsupportedInterpolation()
    if (source.isMarkedNullable || target.isMarkedNullable) return unsupportedInterpolation()
    if (target.classId.asSingleFqName().asString() != "kotlin.String") return unsupportedInterpolation()
    return scalarClass(source.classId.asSingleFqName().asString())
}

private fun scalarClass(qualifiedName: String): Refinement<ValueInterpolationScalarKind, ValueFlowUnsupportedCause> =
    when (qualifiedName) {
        "kotlin.String" -> Refinement.Refined(ValueInterpolationScalarKind.STRING)
        "kotlin.Boolean" -> Refinement.Refined(ValueInterpolationScalarKind.BOOLEAN)
        "kotlin.Char" -> Refinement.Refined(ValueInterpolationScalarKind.CHAR)
        "kotlin.Byte" -> Refinement.Refined(ValueInterpolationScalarKind.BYTE)
        "kotlin.Short" -> Refinement.Refined(ValueInterpolationScalarKind.SHORT)
        "kotlin.Int" -> Refinement.Refined(ValueInterpolationScalarKind.INT)
        "kotlin.Long" -> Refinement.Refined(ValueInterpolationScalarKind.LONG)
        "kotlin.Float" -> Refinement.Refined(ValueInterpolationScalarKind.FLOAT)
        "kotlin.Double" -> Refinement.Refined(ValueInterpolationScalarKind.DOUBLE)
        else -> unsupportedInterpolation()
    }

private fun unsupportedInterpolation(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
