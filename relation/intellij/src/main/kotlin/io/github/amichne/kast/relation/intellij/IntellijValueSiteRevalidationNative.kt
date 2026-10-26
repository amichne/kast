@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLocalVersion
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
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

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
            when (
                val role =
                    positionRole(
                        project = project,
                        request = request,
                        model = model,
                        reservedWork = work,
                        started = started,
                    )
            ) {
                is Refinement.Refined -> boundedPosition(request, role.value, work, started)
                is Refinement.Rejected ->
                    when (val failure = role.failure) {
                        is NativeSiteFailure.Native -> ValueModelSiteRead.Rejected(failure.cause)
                        is NativeSiteFailure.Unsupported -> ValueModelSiteRead.Unsupported(failure.cause)
                        is NativeSiteFailure.Limited -> ValueModelSiteRead.Limited(failure.cause)
                    }
            }
        }

    private fun positionRole(
        project: Project,
        request: ValueSiteRevalidationRequest,
        model: WorkspaceSearchScopeModelCompilation,
        reservedWork: Long,
        started: Long,
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
                    started = started,
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
        started: Long,
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
        return nativeAdmittedPosition(
            element = element,
            claim = request.role,
            prepared = prepared,
            execution = execution,
            work =
                NativeComponentSiteWork(request.budget.resources, reservedWork + execution.examinedWork.value, started),
        )
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
                        localVersion = position.localVersion,
                    )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return ValueModelSiteRead.Rejected(ValueFlowRejection.STALE_SITE)
            }
        if (!request.budget.permitsReturnedBytes(site.retainedBytes))
            return ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.BYTE_LIMIT_REACHED)
        return when (val result = RevalidatedValueSite.fromCompiler(request, site)) {
            is Refinement.Refined ->
                ValueModelSiteRead.Revalidated(result.value, siteWorkCount(work + position.additionalWork.value))
            is Refinement.Rejected -> ValueModelSiteRead.ContractRejected(result.failure)
        }
    }
}

private fun nativeAdmittedPosition(
    element: PsiElement,
    claim: ValueSiteRoleClaim,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    execution: NativeInitialValueRead,
    work: NativeComponentSiteWork,
): Refinement<NativeValuePosition, NativeSiteFailure> {
    val mutable = element.isMutableDefinitionClaim(claim)
    val role =
        if (mutable && element !is KtDestructuringDeclarationEntry) ValueRole.LocalBinding
        else
            when (
                val result =
                    nativeValueSiteRole(
                        element = element,
                        claim = claim,
                        prepared = prepared,
                        executionOwner = execution.owner,
                        componentPermit = work::permit,
                    )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(nativePositionFailure(result.failure))
            }
    val localVersion =
        if (mutable)
            when (
                val version =
                    nativeLocalValueVersion(
                        definition = element,
                        execution = execution.owner,
                        observation = prepared.projection.observation,
                        permit = work::permit,
                    )
            ) {
                is Refinement.Refined -> version.value.proof
                is Refinement.Rejected -> return Refinement.Rejected(nativePositionFailure(version.failure))
            }
        else ValueLocalVersion.Unversioned
    return Refinement.Refined(
        NativeValuePosition(
            role = role,
            executionOwner = execution.owner,
            additionalWork = siteWorkCount(execution.examinedWork.value + work.examined),
            localVersion = localVersion,
        )
    )
}

private fun PsiElement.isMutableDefinitionClaim(claim: ValueSiteRoleClaim): Boolean =
    claim == ValueSiteRoleClaim.LocalBinding &&
        when (this) {
            is org.jetbrains.kotlin.psi.KtProperty -> isVar
            is org.jetbrains.kotlin.psi.KtBinaryExpression -> true
            is KtDestructuringDeclarationEntry -> (parent as? KtDestructuringDeclaration)?.isVar == true
            else -> false
        }

private fun nativePositionFailure(cause: ValueFlowUnsupportedCause): NativeSiteFailure =
    when (cause) {
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED ->
            NativeSiteFailure.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED)
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED ->
            NativeSiteFailure.Limited(ValueSiteRevalidationLimit.TIME_LIMIT_REACHED)
        else -> NativeSiteFailure.Unsupported(cause)
    }

private class NativeComponentSiteWork(
    private val resources: ResourceBudget,
    private val reservedWork: Long,
    started: Long,
) {
    private val allowance = IntellijRelationAllowance(started) { System.nanoTime() }
    val examined: Long
        get() = allowance.examined

    fun permit(): Refinement<Unit, ValueFlowUnsupportedCause> =
        when {
            !resources.hasWorkCapacity(reservedWork + examined) ->
                Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            allowance.elapsedLimitReached(resources) ->
                Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
            else -> {
                allowance.examine()
                Refinement.Refined(Unit)
            }
        }
}

private data class NativeValuePosition(
    val role: ValueRole,
    val executionOwner: ValueExecutionOwner,
    val additionalWork: RelationWorkCount,
    val localVersion: ValueLocalVersion = ValueLocalVersion.Unversioned,
)

private sealed interface NativeSiteFailure {
    data class Native(val cause: ValueFlowRejection) : NativeSiteFailure

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeSiteFailure

    data class Limited(val cause: ValueSiteRevalidationLimit) : NativeSiteFailure
}

private fun nativeSiteRejected(cause: ValueFlowRejection): Refinement.Rejected<NativeSiteFailure> =
    Refinement.Rejected(NativeSiteFailure.Native(cause))

private const val ARGUMENT_SITE_WORK = 3L
private const val EXPRESSION_SITE_WORK = 2L

private fun siteWorkCount(count: Long): RelationWorkCount =
    when (val result = RelationWorkCount.parse(count)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("Native stage count is nonnegative")
    }
