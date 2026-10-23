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
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
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
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression
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
            val work =
                when (request.role) {
                    is ValueSiteRoleClaim.Argument,
                    is ValueSiteRoleClaim.Receiver -> ARGUMENT_SITE_WORK
                    else -> EXPRESSION_SITE_WORK
                }
            if (!request.budget.resources.permitsWork(work))
                return@nativeBoundary ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED)
            val started = System.nanoTime()
            when (val role = positionRole(project = project, request = request, model = model, reservedWork = work)) {
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
        reservedWork: Long,
    ): Refinement<NativeValuePosition, NativeSiteFailure> {
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
            is Refinement.Refined ->
                admittedRole(
                    element = restored.value,
                    request = request,
                    prepared = prepared,
                    reservedWork = reservedWork,
                )
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
        reservedWork: Long,
    ): Refinement<NativeValuePosition, NativeSiteFailure> {
        val enclosing = RelationEndpoint.subject(request.enclosing)
        val execution =
            when (
                val admitted =
                    nativeValueInitialExecution(
                        element = element,
                        host = prepared.owner,
                        enclosing = enclosing,
                        scope = prepared.scope,
                        projection = prepared.projection,
                        reservedWork = reservedWork,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return when (val failure = admitted.failure) {
                        is NativeInitialValueFailure.Native -> nativeSiteRejected(failure.cause)
                        is NativeInitialValueFailure.Unsupported ->
                            Refinement.Rejected(NativeSiteFailure.Unsupported(failure.cause))
                    }
            }
        return when (
            val result =
                nativeRole(
                    element = element,
                    claim = request.role,
                    prepared = prepared,
                    observation = observation,
                    executionOwner = execution.owner,
                )
        ) {
            is Refinement.Refined ->
                Refinement.Refined(NativeValuePosition(result.value, execution.owner, execution.examinedWork))
            is Refinement.Rejected -> Refinement.Rejected(NativeSiteFailure.Unsupported(result.failure))
        }
    }

    private fun boundedPosition(
        request: ValueSiteRevalidationRequest,
        position: NativeValuePosition,
        work: Long,
        started: Long,
    ): ValueModelSiteRead {
        if (
            !request.budget.resources.hasTimeRemaining(
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0L))
            )
        )
            return ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.TIME_LIMIT_REACHED)
        val site =
            when (
                val result =
                    ValueSite.fromCompiler(
                        enclosing = RelationEndpoint.subject(request.enclosing),
                        range = request.anchor,
                        role = position.role,
                        executionOwner = position.executionOwner,
                    )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return ValueModelSiteRead.Rejected(ValueFlowRejection.STALE_SITE)
            }
        if (!request.budget.permitsReturnedBytes(site.retainedBytes))
            return ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.BYTE_LIMIT_REACHED)
        return when (val result = RevalidatedValueSite.fromCompiler(request, site)) {
            is Refinement.Refined ->
                ValueModelSiteRead.Revalidated(result.value, siteWorkCount(work + position.captureWork.value))
            is Refinement.Rejected -> ValueModelSiteRead.ContractRejected(result.failure)
        }
    }
}

private data class NativeValuePosition(
    val role: ValueRole,
    val executionOwner: ValueExecutionOwner,
    val captureWork: RelationWorkCount,
)

private fun nativeRole(
    element: PsiElement,
    claim: ValueSiteRoleClaim,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    observation: IntellijReadObservation,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    when (claim) {
        ValueSiteRoleClaim.ExpressionResult -> expressionRole(element, observation)
        ValueSiteRoleClaim.LocalBinding -> localBindingRole(element, observation)
        ValueSiteRoleClaim.PropertyAssignment -> propertyRole(element, observation)
        ValueSiteRoleClaim.LocalRead -> localReadRole(element, prepared.owner, observation)
        ValueSiteRoleClaim.Return -> returnRole(element, observation)
        is ValueSiteRoleClaim.Argument -> argumentRole(element, prepared, executionOwner)
        is ValueSiteRoleClaim.Receiver -> receiverRole(element, prepared, executionOwner)
    }

private fun expressionRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element !is KtNamedDeclaration && element.hasNativeType(observation))
        Refinement.Refined(ValueRole.ExpressionResult)
    else roleRejected()

private fun localBindingRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element.nativeLocalBindingMutability() is Refinement.Refined && element.hasNativeLocalDeclaration(observation))
        Refinement.Refined(ValueRole.LocalBinding)
    else roleRejected()

private fun propertyRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val property = element as? KtProperty ?: return roleRejected()
    return if (!property.isLocal && property.initializer != null && property.hasNativeDeclaration(observation))
        Refinement.Refined(ValueRole.PropertyAssignment)
    else roleRejected()
}

private fun localReadRole(
    element: PsiElement,
    owner: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (element is KtThisExpression) {
        val function = owner as? KtNamedFunction ?: return roleRejected()
        if (valueNativeOwnership(element, function) != NativeValueOwnership.ADMITTED) return roleRejected()
        return when (val receiver = nativeThisReceiverKind(function, element, observation)) {
            is Refinement.Refined -> Refinement.Refined(ValueRole.LocalRead)
            is Refinement.Rejected -> receiver
        }
    }
    return if (element is KtExpression && element.hasNativeLocalReference(observation))
        Refinement.Refined(ValueRole.LocalRead)
    else roleRejected()
}

private fun returnRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element.hasNativeType(observation) && element.isReturnedExpression())
        Refinement.Refined(ValueRole.Return)
    else roleRejected()

private fun argumentRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return roleRejected()
    val argument = element.parent as? KtValueArgument ?: return roleRejected()
    return nativeValueArgument(
        expression = expression,
        argument = argument,
        scope = prepared.scope,
        projection = prepared.projection,
        executionOwner = executionOwner,
    )
}

private fun receiverRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return roleRejected()
    val qualified = element.parent as? KtQualifiedExpression ?: return roleRejected()
    return nativeValueReceiver(
        expression = expression,
        qualified = qualified,
        scope = prepared.scope,
        projection = prepared.projection,
        executionOwner = executionOwner,
    )
}

private sealed interface NativeSiteFailure {
    data class Native(val cause: ValueFlowRejection) : NativeSiteFailure

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeSiteFailure
}

private fun nativeSiteRejected(cause: ValueFlowRejection): Refinement.Rejected<NativeSiteFailure> =
    Refinement.Rejected(NativeSiteFailure.Native(cause))

private const val ARGUMENT_SITE_WORK = 3L
private const val EXPRESSION_SITE_WORK = 2L

private fun KtExpression.hasNativeType(observation: IntellijReadObservation): Boolean =
    observation.observedAnalyze(this) {
        val type = expressionType
        type != null && type !is KaErrorType
    }

private fun KtProperty.hasNativeDeclaration(observation: IntellijReadObservation): Boolean =
    observation.observedAnalyze(this) { symbol.psi === this@hasNativeDeclaration }

private fun PsiElement.hasNativeLocalDeclaration(observation: IntellijReadObservation): Boolean =
    when (this) {
        is KtProperty -> hasNativeDeclaration(observation)
        is KtDestructuringDeclarationEntry -> {
            val entry = this
            observation.observedAnalyze(entry) {
                val resolved = entry.symbol
                resolved is KaLocalVariableSymbol &&
                    resolved.origin == KaSymbolOrigin.SOURCE &&
                    resolved.psi?.originalElement === entry.originalElement
            }
        }
        else -> false
    }

private fun KtExpression.hasNativeLocalReference(observation: IntellijReadObservation): Boolean {
    val reference = references.filterIsInstance<KtReference>().singleOrNull() ?: return false
    return when (val resolved = resolveLocalValueReference(reference, observation)) {
        is NativeLocalValueReferenceResolution.Resolved ->
            resolved.declaration.nativeLocalBindingMutability() is Refinement.Refined
        NativeLocalValueReferenceResolution.Unresolved -> false
    }
}

private fun KtExpression.isReturnedExpression(): Boolean =
    when (val container = parent) {
        is KtReturnExpression -> container.returnedExpression === this
        is KtNamedFunction -> container.bodyExpression === this
        is org.jetbrains.kotlin.psi.KtBlockExpression ->
            container.parent is org.jetbrains.kotlin.psi.KtFunctionLiteral && container.statements.lastOrNull() === this
        else -> false
    }

private fun roleRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

private fun siteWorkCount(count: Long): RelationWorkCount =
    when (val result = RelationWorkCount.parse(count)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("Native stage count is nonnegative")
    }
