@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationLimit
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/** Revalidates one exact claimed role without exploring a flow or attaching model vocabulary. */
internal class IntellijValueSiteRevalidationNative(
    private val adapter: IntellijValueFlowCompilerAdapter,
    private val observation: IntellijReadObservation,
) {
    suspend fun revalidate(
        project: Project,
        current: SemanticReadAuthority,
        request: ValueSiteRevalidationRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelSiteRead =
        adapter.nativeBoundary(
            project,
            current,
            request.enclosing.lease,
            { ValueModelSiteRead.Rejected(it) },
            IntellijReadStage.VALUE_MODEL_SITE_ADMISSION,
        ) {
            val work = if (request.role is ValueSiteRoleClaim.Argument) ARGUMENT_SITE_WORK else EXPRESSION_SITE_WORK
            if (request.budget.resources.workUnitLimit.value < work)
                return@nativeBoundary ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED)
            val started = System.nanoTime()
            when (val role = positionRole(project, request, model)) {
                is Refinement.Refined -> boundedPosition(request, role.value, work, started)
                is Refinement.Rejected ->
                    when (val failure = role.failure) {
                        is NativeSiteFailure.Native -> ValueModelSiteRead.Rejected(failure.cause)
                        is NativeSiteFailure.Unsupported -> ValueModelSiteRead.Unsupported(failure.cause)
                    }
            }
        }

    private fun positionRole(
        project: Project,
        request: ValueSiteRevalidationRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): Refinement<ValueRole, NativeSiteFailure> {
        // The exact owner is still restored under its retained selector scope. Workspace permits an actual
        // callee in another file; this validation never adopts or enumerates the investigation's D.
        val selection =
            RelationRequest.start(
                request.enclosing,
                RelationMeaning.References,
                request.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val prepared =
            when (val result = adapter.prepare(project, selection, model)) {
                is IntellijValueFlowCompilerAdapter.Prepared.Ready -> result
                is IntellijValueFlowCompilerAdapter.Prepared.Rejected -> return nativeSiteRejected(result.cause)
            }
        return when (val restored = prepared.owner.restoreValueSiteElement(request.anchor, request.role, observation)) {
            is Refinement.Refined -> admittedRole(restored.value, request, prepared)
            is Refinement.Rejected ->
                when (restored.failure) {
                    NativeValueSiteRestorationFailure.ANCHOR_UNAVAILABLE ->
                        nativeSiteRejected(ValueFlowRejection.STALE_SITE)
                    NativeValueSiteRestorationFailure.ROLE_SHAPE_UNSUPPORTED ->
                        Refinement.Rejected(
                            NativeSiteFailure.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                        )
                }
        }
    }

    private fun admittedRole(
        element: PsiElement,
        request: ValueSiteRevalidationRequest,
        prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    ): Refinement<ValueRole, NativeSiteFailure> {
        when (valueNativeOwnership(element, prepared.owner)) {
            NativeValueOwnership.ADMITTED -> Unit
            NativeValueOwnership.NESTED -> return nativeSiteRejected(ValueFlowRejection.NESTED_EXECUTION)
            NativeValueOwnership.UNAVAILABLE -> return nativeSiteRejected(ValueFlowRejection.OWNER_UNAVAILABLE)
        }
        return when (
            val result = nativeRole(element, request.role, prepared, RelationEndpoint.subject(request.enclosing))
        ) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> Refinement.Rejected(NativeSiteFailure.Unsupported(result.failure))
        }
    }

    private fun boundedPosition(
        request: ValueSiteRevalidationRequest,
        role: ValueRole,
        work: Long,
        started: Long,
    ): ValueModelSiteRead {
        if (
            System.nanoTime() - started >=
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(request.budget.resources.elapsedTimeLimit.value)
        )
            return ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.TIME_LIMIT_REACHED)
        val site =
            when (
                val result = ValueSite.fromCompiler(RelationEndpoint.subject(request.enclosing), request.anchor, role)
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return ValueModelSiteRead.Rejected(ValueFlowRejection.STALE_SITE)
            }
        if (site.retainedBytes > request.budget.returnedBytes.value)
            return ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.BYTE_LIMIT_REACHED)
        return when (val result = RevalidatedValueSite.fromCompiler(request, site)) {
            is Refinement.Refined -> ValueModelSiteRead.Revalidated(result.value, siteWorkCount(work))
            is Refinement.Rejected -> ValueModelSiteRead.ContractRejected(result.failure)
        }
    }
}

private fun nativeRole(
    element: PsiElement,
    claim: ValueSiteRoleClaim,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    enclosing: RelationEndpoint,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    when (claim) {
        ValueSiteRoleClaim.ExpressionResult -> expressionRole(element)
        ValueSiteRoleClaim.LocalBinding -> localBindingRole(element)
        ValueSiteRoleClaim.PropertyAssignment -> propertyRole(element)
        ValueSiteRoleClaim.LocalRead -> localReadRole(element)
        ValueSiteRoleClaim.Return -> returnRole(element)
        is ValueSiteRoleClaim.Argument -> argumentRole(element, enclosing, prepared)
    }

private fun expressionRole(element: PsiElement): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element !is KtNamedDeclaration && element.hasNativeType())
        Refinement.Refined(ValueRole.ExpressionResult)
    else roleRejected()

private fun localBindingRole(element: PsiElement): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtProperty && element.isLocal && element.hasNativeDeclaration())
        Refinement.Refined(ValueRole.LocalBinding)
    else roleRejected()

private fun propertyRole(element: PsiElement): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val property = element as? KtProperty ?: return roleRejected()
    return if (!property.isLocal && property.initializer != null && property.hasNativeDeclaration())
        Refinement.Refined(ValueRole.PropertyAssignment)
    else roleRejected()
}

private fun localReadRole(element: PsiElement): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element.hasNativeLocalReference()) Refinement.Refined(ValueRole.LocalRead)
    else roleRejected()

private fun returnRole(element: PsiElement): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element.hasNativeType() && element.isReturnedExpression())
        Refinement.Refined(ValueRole.Return)
    else roleRejected()

private fun argumentRole(
    element: PsiElement,
    enclosing: RelationEndpoint,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return roleRejected()
    val argument = element.parent as? KtValueArgument ?: return roleRejected()
    return nativeValueArgument(expression, argument, enclosing, prepared.scope, prepared.projection)
}

private sealed interface NativeSiteFailure {
    data class Native(val cause: ValueFlowRejection) : NativeSiteFailure

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeSiteFailure
}

private fun nativeSiteRejected(cause: ValueFlowRejection): Refinement.Rejected<NativeSiteFailure> =
    Refinement.Rejected(NativeSiteFailure.Native(cause))

private const val ARGUMENT_SITE_WORK = 3L
private const val EXPRESSION_SITE_WORK = 2L

private fun KtExpression.hasNativeType(): Boolean =
    analyze(this) {
        val type = expressionType
        type != null && type !is KaErrorType
    }

private fun KtProperty.hasNativeDeclaration(): Boolean = analyze(this) { symbol.psi === this@hasNativeDeclaration }

private fun KtExpression.hasNativeLocalReference(): Boolean {
    val reference = references.filterIsInstance<KtReference>().singleOrNull() ?: return false
    return analyze(this) { (reference.resolveToSymbol()?.psi as? KtProperty)?.isLocal == true }
}

private fun KtExpression.isReturnedExpression(): Boolean =
    when (val container = parent) {
        is KtReturnExpression -> container.returnedExpression === this
        is KtNamedFunction -> container.bodyExpression === this
        else -> false
    }

private fun roleRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

private fun siteWorkCount(count: Long): RelationWorkCount =
    when (val result = RelationWorkCount.parse(count)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("Native stage count is nonnegative")
    }
