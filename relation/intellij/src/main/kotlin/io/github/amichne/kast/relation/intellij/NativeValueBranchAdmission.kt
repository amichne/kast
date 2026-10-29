@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.types.Variance

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
    binding: PsiElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission =
    projection.observation.observedAnalyze(expression) {
        val expected =
            when (binding) {
                is KtFunction -> {
                    val receiver = (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Receiver
                    receiver?.kind?.let { nativeValueReceiverSymbol(binding, it) }
                }
                is KtNamedDeclaration -> binding.symbol
                else -> null
            }
        val branch = branchAdmission(source, expression, expected, scope, projection, workAllowance)
        when (branch) {
            NativeValueBranchAdmission.ADMITTED,
            NativeValueBranchAdmission.NOT_APPLICABLE ->
                when (val condition = versionIfAdmission(source, expression, expected, workAllowance)) {
                    NativeValueBranchAdmission.NOT_APPLICABLE -> branch
                    NativeValueBranchAdmission.ADMITTED,
                    NativeValueBranchAdmission.EXCLUDED,
                    NativeValueBranchAdmission.UNPROVEN,
                    NativeValueBranchAdmission.WORK_LIMIT_REACHED -> condition
                }
            NativeValueBranchAdmission.EXCLUDED,
            NativeValueBranchAdmission.UNPROVEN,
            NativeValueBranchAdmission.WORK_LIMIT_REACHED -> branch
        }
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
    val exactType =
        trackedFinalOwnerType(source, scope, projection)
            ?: versionFinalType(source, expression)
            ?: return NativeValueBranchAdmission.UNPROVEN
    return when (nativeCompilerTypeProof(listOf(exactType), workAllowance)) {
        NativeCompilerTypeProof.ADMITTED ->
            when (val branch = matchingArm(exactType, whenExpression, entry, workAllowance)) {
                NativeValueBranchAdmission.ADMITTED ->
                    admitVersionSmartcast(source, expression, exactType, workAllowance)
                NativeValueBranchAdmission.NOT_APPLICABLE,
                NativeValueBranchAdmission.EXCLUDED,
                NativeValueBranchAdmission.UNPROVEN,
                NativeValueBranchAdmission.WORK_LIMIT_REACHED -> branch
            }
        NativeCompilerTypeProof.WORK_LIMIT_REACHED -> NativeValueBranchAdmission.WORK_LIMIT_REACHED
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> NativeValueBranchAdmission.UNPROVEN
    }
}

/** The admitted definition identifies the RHS; K2's final non-null type establishes the exact possible class. */
private fun KaSession.versionFinalType(source: ValueSite, expression: KtExpression): KaClassType? {
    val version = source.localVersion as? ValueLocalVersion.Defined ?: return null
    if (source.valuePath.segments.isNotEmpty()) return null
    val rhs = expression.containingFile.exactValueElement(version.rhs) ?: return null
    val type = rhs.expressionType as? KaClassType ?: return null
    return type.takeIf {
        !it.isMarkedNullable &&
            it.symbol.modality == KaSymbolModality.FINAL &&
            it.qualifiers.none { qualifier -> qualifier.typeArguments.isNotEmpty() }
    }
}

private fun KaSession.versionIfAdmission(
    source: ValueSite,
    expression: KtExpression,
    expected: KaSymbol?,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    if (source.localVersion !is ValueLocalVersion.Defined) return NativeValueBranchAdmission.NOT_APPLICABLE
    val branch =
        PsiTreeUtil.getParentOfType(expression, KtIfExpression::class.java, false)
            ?: return NativeValueBranchAdmission.NOT_APPLICABLE
    val inThen = branch.then?.let { PsiTreeUtil.isAncestor(it, expression, false) } == true
    val inElse = branch.`else`?.let { PsiTreeUtil.isAncestor(it, expression, false) } == true
    if (!inThen && !inElse) return NativeValueBranchAdmission.NOT_APPLICABLE
    val condition = branch.condition as? KtIsExpression
    if (condition == null) {
        // The version proves the value; the compiler, rather than a syntax whitelist, proves its use-site type.
        val exact = versionFinalType(source, expression) ?: return NativeValueBranchAdmission.UNPROVEN
        if (expression.smartCastInfo == null) return NativeValueBranchAdmission.UNPROVEN
        return admitVersionSmartcast(source, expression, exact, workAllowance)
    }
    val identity = expected ?: return NativeValueBranchAdmission.UNPROVEN
    when (branchExpressionIdentity(condition.leftHandSide, identity, workAllowance)) {
        NativeBranchIdentity.EXACT -> Unit
        NativeBranchIdentity.WORK_LIMIT_REACHED -> return NativeValueBranchAdmission.WORK_LIMIT_REACHED
        NativeBranchIdentity.OTHER,
        NativeBranchIdentity.UNAVAILABLE -> return NativeValueBranchAdmission.UNPROVEN
    }
    return versionTypeTestAdmission(
        source = source,
        expression = expression,
        condition = condition,
        inThen = inThen,
        workAllowance = workAllowance,
    )
}

private fun KaSession.versionTypeTestAdmission(
    source: ValueSite,
    expression: KtExpression,
    condition: KtIsExpression,
    inThen: Boolean,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    val exact = versionFinalType(source, expression) ?: return NativeValueBranchAdmission.UNPROVEN
    val tested = condition.typeReference?.type as? KaClassType ?: return NativeValueBranchAdmission.UNPROVEN
    if (!tested.hasOnlyStarArguments()) return NativeValueBranchAdmission.UNPROVEN
    when (nativeCompilerTypeProof(listOf(exact, tested), workAllowance)) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED -> return NativeValueBranchAdmission.WORK_LIMIT_REACHED
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return NativeValueBranchAdmission.UNPROVEN
    }
    val matches = exact.isSubtypeOf(tested) != condition.isNegated
    return if (matches != inThen) NativeValueBranchAdmission.EXCLUDED
    else admitVersionSmartcast(source, expression, exact, workAllowance)
}

private fun KaSession.admitVersionSmartcast(
    source: ValueSite,
    expression: KtExpression,
    exactType: KaType,
    workAllowance: WorkAllowance,
): NativeValueBranchAdmission {
    if (source.localVersion !is ValueLocalVersion.Defined) return NativeValueBranchAdmission.ADMITTED
    val fact = expression.smartCastInfo ?: return NativeValueBranchAdmission.ADMITTED
    if (!fact.isStable) return NativeValueBranchAdmission.UNPROVEN
    return when (nativeCompilerTypeProof(listOf(fact.originalType, fact.smartCastType), workAllowance)) {
        NativeCompilerTypeProof.ADMITTED ->
            if (exactType.isSubtypeOf(fact.smartCastType)) NativeValueBranchAdmission.ADMITTED
            else NativeValueBranchAdmission.UNPROVEN
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

/** The caller has resolved this reference to the versioned binding; K2 supplies the scoped, stable type fact. */
internal fun nativeLocalReadConditions(
    source: ValueSite,
    expression: KtExpression,
    admission: NativeValueBranchAdmission,
    observation: IntellijReadObservation,
    allowance: WorkAllowance,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> =
    observation.observedAnalyze(expression) { stableLocalConditions(source, expression, admission, allowance) }

private fun KaSession.stableLocalConditions(
    source: ValueSite,
    expression: KtExpression,
    admission: NativeValueBranchAdmission,
    allowance: WorkAllowance,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val fact = expression.smartCastInfo
    if (fact == null)
        return if (admission == NativeValueBranchAdmission.UNPROVEN) unsupportedLocalRefinement()
        else Refinement.Refined(source.conditions)
    if (!fact.isStable) return unsupportedLocalRefinement()
    when (nativeCompilerTypeProof(listOf(fact.originalType, fact.smartCastType), allowance)) {
        NativeCompilerTypeProof.ADMITTED -> Unit
        NativeCompilerTypeProof.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        NativeCompilerTypeProof.ERROR_TYPE,
        NativeCompilerTypeProof.UNSUPPORTED_TYPE -> return unsupportedLocalRefinement()
    }
    val exact = versionFinalType(source, expression)
    return if (exact != null && exact.isSubtypeOf(fact.smartCastType)) Refinement.Refined(source.conditions)
    else requiringLocalRefinement(source, expression, fact.originalType, fact.smartCastType)
}

private fun KaSession.requiringLocalRefinement(
    source: ValueSite,
    expression: KtExpression,
    originalType: KaType,
    refinedType: KaType,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val original =
        CanonicalCompilerType.fromCompiler(
            originalType.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        )
    val refined =
        CanonicalCompilerType.fromCompiler(
            refinedType.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        )
    if (original !is Refinement.Refined || refined !is Refinement.Refined) return unsupportedLocalRefinement()
    return when (
        val condition =
            ValueCondition.StableLocal.fromCompiler(source, sourceValueRange(expression), original.value, refined.value)
    ) {
        is Refinement.Rejected -> unsupportedLocalRefinement()
        is Refinement.Refined ->
            when (val conditions = source.conditions.requiring(condition.value)) {
                is Refinement.Refined -> conditions
                is Refinement.Rejected -> unsupportedLocalRefinement()
            }
    }
}

private fun unsupportedLocalRefinement(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
