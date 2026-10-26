@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

internal enum class NativeValueBranchAdmission {
    ADMITTED,
    NOT_APPLICABLE,
    EXCLUDED,
    UNPROVEN,
    WORK_LIMIT_REACHED,
}

/** A tracked field on a final owner proves only that owner's compatible type-test arms. */
internal fun nativeValueBranchAdmission(
    source: ValueSite,
    expression: KtExpression,
    binding: KtNamedDeclaration,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission =
    projection.observation.observedAnalyze(expression) {
        val expected =
            when (binding) {
                is KtNamedFunction -> {
                    val receiver = (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver
                    when (receiver?.kind) {
                        ValueReceiverKind.EXTENSION -> binding.symbol.receiverParameter
                        ValueReceiverKind.DISPATCH -> binding.symbol.containingDeclaration
                        null -> null
                    }
                }
                else -> binding.symbol
            }
        branchAdmission(source, expression, expected, scope, projection, workAllowance)
    }

internal fun KaSession.branchAdmission(
    source: ValueSite,
    expression: KtExpression,
    expected: KaSymbol?,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    var entry =
        PsiTreeUtil.getParentOfType(expression, KtWhenEntry::class.java, false)
            ?: return NativeValueBranchAdmission.NOT_APPLICABLE
    val identity = expected ?: return NativeValueBranchAdmission.UNPROVEN
    var admission = NativeValueBranchAdmission.NOT_APPLICABLE
    var examined = 0L
    while (true) {
        val whenExpression = entry.parent as? KtWhenExpression ?: return NativeValueBranchAdmission.UNPROVEN
        val subject = whenExpression.subjectVariable?.initializer ?: whenExpression.subjectExpression
        examined++
        if (!workAllowance.permits(examined)) return NativeValueBranchAdmission.WORK_LIMIT_REACHED
        val sameSubject =
            if (whenExpression.subjectVariable?.symbol == identity) NativeBranchIdentity.EXACT
            else branchExpressionIdentity(subject, identity, workAllowance)
        when (sameSubject) {
            NativeBranchIdentity.EXACT -> {
                when (
                    val admitted =
                        admitExactBranch(source, expression, whenExpression, entry, scope, projection, workAllowance)
                ) {
                    NativeValueBranchAdmission.ADMITTED -> admission = admitted
                    NativeValueBranchAdmission.NOT_APPLICABLE,
                    NativeValueBranchAdmission.EXCLUDED,
                    NativeValueBranchAdmission.UNPROVEN,
                    NativeValueBranchAdmission.WORK_LIMIT_REACHED -> return admitted
                }
            }
            NativeBranchIdentity.OTHER,
            NativeBranchIdentity.UNAVAILABLE -> Unit
            NativeBranchIdentity.WORK_LIMIT_REACHED -> return NativeValueBranchAdmission.WORK_LIMIT_REACHED
        }
        entry = PsiTreeUtil.getParentOfType(whenExpression, KtWhenEntry::class.java, true) ?: return admission
    }
}

private fun KaSession.admitExactBranch(
    source: ValueSite,
    expression: KtExpression,
    whenExpression: KtWhenExpression,
    entry: KtWhenEntry,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    if (PsiTreeUtil.getParentOfType(expression, KtIfExpression::class.java, false) != null)
        return NativeValueBranchAdmission.UNPROVEN
    val exactType = trackedFinalOwnerType(source, scope, projection) ?: return NativeValueBranchAdmission.UNPROVEN
    return when (nativeCompilerTypeProof(listOf(exactType), workAllowance)) {
        NativeCompilerTypeProof.ADMITTED -> matchingArm(exactType, whenExpression, entry, workAllowance)
        NativeCompilerTypeProof.WORK_LIMIT_REACHED -> NativeValueBranchAdmission.WORK_LIMIT_REACHED
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> NativeValueBranchAdmission.UNPROVEN
    }
}

private fun KaSession.trackedFinalOwnerType(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): KaType? {
    val field = (source.valuePath.segments.firstOrNull() as? ValuePathSegment.Field)?.field ?: return null
    val declaration =
        when (val selected = projection.subject(scope, field.declaration)) {
            is IntellijRelationSubjectLookup.Found -> selected.declaration
            is IntellijRelationSubjectLookup.Rejected -> return null
        }
    val owner = PsiTreeUtil.getParentOfType(declaration, KtClass::class.java, true) ?: return null
    val symbol = owner.symbol as? KaNamedClassSymbol ?: return null
    if (symbol.modality != KaSymbolModality.FINAL) return null
    return symbol.defaultTypeWithStarProjections
}

private fun KaSession.matchingArm(
    exactType: KaType,
    expression: KtWhenExpression,
    selected: KtWhenEntry,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    for ((index, entry) in expression.entries.withIndex()) {
        if (!workAllowance.permits(index.toLong() + 1L)) return NativeValueBranchAdmission.WORK_LIMIT_REACHED
        val matches =
            when (val result = branchConditionMatches(exactType, entry, workAllowance)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result.failure
            }
        if (entry === selected && matches) return NativeValueBranchAdmission.ADMITTED
        if (entry === selected || matches) return NativeValueBranchAdmission.EXCLUDED
    }
    return NativeValueBranchAdmission.UNPROVEN
}

private fun KaSession.branchConditionMatches(
    exactType: KaType,
    entry: KtWhenEntry,
    workAllowance: WorkAllowance,
): Refinement<Boolean, NativeValueBranchAdmission> {
    if (entry.guard != null) return Refinement.Rejected(NativeValueBranchAdmission.UNPROVEN)
    if (entry.isElse) return Refinement.Refined(true)
    val condition =
        entry.conditions.singleOrNull() as? KtWhenConditionIsPattern
            ?: return Refinement.Rejected(NativeValueBranchAdmission.UNPROVEN)
    val tested =
        condition.typeReference?.type as? KaClassType ?: return Refinement.Rejected(NativeValueBranchAdmission.UNPROVEN)
    if (!tested.hasOnlyStarArguments()) return Refinement.Rejected(NativeValueBranchAdmission.UNPROVEN)
    return when (nativeCompilerTypeProof(listOf(tested), workAllowance)) {
        NativeCompilerTypeProof.ADMITTED -> {
            val subtype = exactType.isSubtypeOf(tested)
            Refinement.Refined(if (condition.isNegated) !subtype else subtype)
        }
        NativeCompilerTypeProof.WORK_LIMIT_REACHED -> Refinement.Rejected(NativeValueBranchAdmission.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> Refinement.Rejected(NativeValueBranchAdmission.UNPROVEN)
    }
}

private fun KaClassType.hasOnlyStarArguments(): Boolean =
    typeArguments.all { it is KaStarTypeProjection } &&
        qualifiers.all { qualifier -> qualifier.typeArguments.all { it is KaStarTypeProjection } }
