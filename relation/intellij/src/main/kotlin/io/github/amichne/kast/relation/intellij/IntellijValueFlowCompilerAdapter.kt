@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty

sealed interface ValueSiteResolution {
    data class Found(val site: ValueSite) : ValueSiteResolution

    data class Rejected(val cause: ValueFlowRejection) : ValueSiteResolution
}

/** Native one-hop adapter. Query owns work scheduling, publication and continuation state. */
class IntellijValueFlowCompilerAdapter(
    internal val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    suspend fun seed(
        project: Project,
        current: SemanticReadAuthority,
        request: ValueProducerSeedRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueProducerSeedRead =
        IntellijValueProducerSeedNative(this).seed(project, current, request, model).also {
            observation.count(IntellijReadCounter.VALUE_PRODUCER_SEED_READS)
            when (it) {
                is ValueProducerSeedRead.Seeded -> observation.count(IntellijReadCounter.VALUE_PRODUCER_SEEDS_CONFIRMED)
                is ValueProducerSeedRead.Rejected,
                is ValueProducerSeedRead.ContractRejected ->
                    observation.count(IntellijReadCounter.VALUE_PRODUCER_SEED_REJECTIONS)
            }
        }

    suspend fun revalidate(
        project: Project,
        current: SemanticReadAuthority,
        selector: SymbolSelector,
        budget: RelationBudget,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelDeclarationRead =
        IntellijValueProducerSeedNative(this).revalidate(project, current, selector, budget, model).also {
            observation.count(IntellijReadCounter.VALUE_MODEL_REVALIDATIONS)
            if (it is ValueModelDeclarationRead.Rejected)
                observation.count(IntellijReadCounter.VALUE_MODEL_REVALIDATIONS_REJECTED)
        }

    suspend fun revalidateSite(
        project: Project,
        current: SemanticReadAuthority,
        request: ValueSiteRevalidationRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelSiteRead =
        IntellijValueSiteRevalidationNative(this, observation).revalidate(project, current, request, model).also {
            observation.count(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS)
            if (it !is ValueModelSiteRead.Revalidated)
                observation.count(IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED)
        }

    suspend fun resolve(
        project: Project,
        current: SemanticReadAuthority,
        enclosing: SymbolSelector,
        anchor: ExactDeclarationTextRange,
        domain: RelationRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueSiteResolution =
        nativeBoundary(
                project,
                current,
                enclosing.lease,
                { ValueSiteResolution.Rejected(it) },
            ) {
                if (domain.subject.lease != current || !domain.subject.matchesDeclaration(enclosing))
                    return@nativeBoundary ValueSiteResolution.Rejected(ValueFlowRejection.STALE_SITE)
                when (val prepared = prepare(project, domain, model)) {
                    is Prepared.Rejected -> ValueSiteResolution.Rejected(prepared.cause)
                    is Prepared.Ready -> resolvePrepared(prepared, enclosing, anchor)
                }
            }
            .also {
                if (it is ValueSiteResolution.Rejected) observation.count(IntellijReadCounter.VALUE_FLOW_REJECTIONS)
            }

    private fun resolvePrepared(
        prepared: Prepared.Ready,
        enclosing: SymbolSelector,
        anchor: ExactDeclarationTextRange,
    ): ValueSiteResolution {
        val expression =
            prepared.owner.exactValueElement(anchor)
                ?: return ValueSiteResolution.Rejected(ValueFlowRejection.UNSUPPORTED_SEED)
        when (val admitted = admittedOwnership(expression, prepared.owner)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return ValueSiteResolution.Rejected(admitted.failure)
        }
        if (expression is KtNamedDeclaration) return ValueSiteResolution.Rejected(ValueFlowRejection.UNSUPPORTED_SEED)
        val nativeType =
            observation.observedAnalyze(expression) {
                val type = expression.expressionType
                if (type == null || type is KaErrorType) NativeValueType.UNRESOLVED else NativeValueType.RESOLVED
            }
        if (nativeType == NativeValueType.UNRESOLVED)
            return ValueSiteResolution.Rejected(ValueFlowRejection.UNRESOLVED_SEED)
        return when (
            val site = ValueSite.fromCompiler(RelationEndpoint.subject(enclosing), anchor, ValueRole.ExpressionResult)
        ) {
            is Refinement.Refined -> ValueSiteResolution.Found(site.value)
            is Refinement.Rejected -> ValueSiteResolution.Rejected(ValueFlowRejection.STALE_SITE)
        }
    }

    suspend fun read(
        project: Project,
        current: SemanticReadAuthority,
        request: ValueFlowRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueFlowRead {
        var observedWork = flowWorkCount(0)
        return nativeBoundary(
                project,
                current,
                request.source.enclosing.lease,
                { ValueFlowRead.Rejected(it, observedWork) },
            ) {
                val started = System.nanoTime()
                observation.count(IntellijReadCounter.VALUE_FLOW_READS)
                val owner = request.source.enclosing
                val domain = RelationRequest.start(owner, RelationMeaning.References, request.budget, request.boundary)
                observedWork = flowWorkCount(1)
                when (val prepared = prepare(project, domain, model)) {
                    is Prepared.Rejected -> ValueFlowRead.Rejected(prepared.cause, observedWork)
                    is Prepared.Ready -> readPrepared(prepared, request, started) { observedWork = it }
                }
            }
            .also {
                if (it is ValueFlowRead.Rejected || it is ValueFlowRead.ContractRejected)
                    observation.count(IntellijReadCounter.VALUE_FLOW_REJECTIONS)
            }
    }

    private fun readPrepared(
        prepared: Prepared.Ready,
        request: ValueFlowRequest,
        started: Long,
        onWork: (RelationWorkCount) -> Unit,
    ): ValueFlowRead {
        val source =
            prepared.owner.exactValueElement(request.source.range)
                ?: return ValueFlowRead.Rejected(ValueFlowRejection.STALE_SITE, flowWorkCount(1))
        when (val admitted = admittedOwnership(source, prepared.owner)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return ValueFlowRead.Rejected(admitted.failure, flowWorkCount(1))
        }
        if (request.source.role == ValueRole.LocalBinding && source !is KtProperty)
            return ValueFlowRead.Rejected(ValueFlowRejection.STALE_SITE, flowWorkCount(1))
        return IntellijValueFlowNative(
                request,
                prepared.owner,
                prepared.scope,
                prepared.projection,
                observation,
                started,
                onWork,
            )
            .read(source)
    }

    private enum class NativeValueType {
        RESOLVED,
        UNRESOLVED,
    }

    internal sealed interface Prepared {
        data class Ready(
            val owner: PsiElement,
            val scope: CompiledRelationScope,
            val projection: IntellijK2RelationProjection,
        ) : Prepared

        data class Rejected(val cause: ValueFlowRejection) : Prepared
    }

    internal fun prepare(
        project: Project,
        request: RelationRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): Prepared {
        val compiler = IntellijRelationScopeCompiler()
        val scope =
            when (val value = compiler.compile(project, request, model, observation = observation)) {
                is IntellijRelationScopeCompilation.Compiled -> value.scope
                is IntellijRelationScopeCompilation.Rejected ->
                    return Prepared.Rejected(ValueFlowRejection.OUTSIDE_DOMAIN)
            }
        val ownerScope =
            when (
                val value =
                    compiler.compile(
                        project,
                        request,
                        model,
                        request.subject.scope,
                        request.subject.constraints,
                        observation,
                    )
            ) {
                is IntellijRelationScopeCompilation.Compiled -> value.scope
                is IntellijRelationScopeCompilation.Rejected ->
                    return Prepared.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE)
            }
        val projection = IntellijK2RelationProjection(project, request.subject.lease.workspaceRoot, observation)
        val owner =
            when (val value = projection.subject(ownerScope, request.subject)) {
                is IntellijRelationSubjectLookup.Found -> value.declaration
                is IntellijRelationSubjectLookup.Rejected -> return Prepared.Rejected(ValueFlowRejection.STALE_SITE)
            }
        if (!scope.nativeScope.contains(owner.containingFile.virtualFile))
            return Prepared.Rejected(ValueFlowRejection.OUTSIDE_DOMAIN)
        return Prepared.Ready(owner, scope, projection)
    }

    // Unexpected JVM/platform failures are bounded finite evidence at this effect boundary.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <Result> nativeBoundary(
        project: Project,
        current: SemanticReadAuthority,
        requested: SemanticReadAuthority,
        rejected: (ValueFlowRejection) -> Result,
        stage: IntellijReadStage = IntellijReadStage.VALUE_FLOW,
        operation: () -> Result,
    ): Result {
        if (current != requested || !current.isCurrentValueAuthority())
            return rejected(ValueFlowRejection.AUTHORITY_MOVED)
        return try {
            if (project.isDisposed || DumbService.isDumb(project))
                return rejected(ValueFlowRejection.NATIVE_UNAVAILABLE)
            val result = readAction { operation() }
            if (current.isCurrentValueAuthority()) result else rejected(ValueFlowRejection.AUTHORITY_MOVED)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            observation.unexpected(IntellijReadUnexpectedFailure.capture(stage, failure, limits))
            rejected(ValueFlowRejection.NATIVE_UNAVAILABLE)
        } catch (failure: LinkageError) {
            observation.unexpected(IntellijReadUnexpectedFailure.capture(stage, failure, limits))
            rejected(ValueFlowRejection.NATIVE_UNAVAILABLE)
        }
    }
}

private fun SemanticReadAuthority.isCurrentValueAuthority(): Boolean =
    when (this) {
        is LiveSemanticReadAuthority -> withCurrentOwner { Unit } is Refinement.Refined
        is SemanticReadLease -> true
    }

/** Exact Kotlin semantic elements exclude same-range lexer tokens and argument container wrappers. */
internal fun PsiElement.exactValueElement(range: ExactDeclarationTextRange): KtExpression? =
    generateSequence(containingFile.findElementAt(range.startInclusive)) { it.parent }
        .takeWhile {
            it.textRange.startOffset >= textRange.startOffset && it.textRange.endOffset <= textRange.endOffset
        }
        .filterIsInstance<KtExpression>()
        .firstOrNull {
            it.textRange.startOffset == range.startInclusive && it.textRange.endOffset == range.endExclusive
        }

private fun flowWorkCount(count: Long): RelationWorkCount =
    when (val refined = RelationWorkCount.parse(count)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> error("Native stage work count is nonnegative")
    }

private fun admittedOwnership(element: PsiElement, owner: PsiElement): Refinement<Unit, ValueFlowRejection> =
    when (valueNativeOwnership(element, owner)) {
        NativeValueOwnership.ADMITTED -> Refinement.Refined(Unit)
        NativeValueOwnership.NESTED -> Refinement.Rejected(ValueFlowRejection.NESTED_EXECUTION)
        NativeValueOwnership.UNAVAILABLE -> Refinement.Rejected(ValueFlowRejection.OWNER_UNAVAILABLE)
    }

private fun RelationEndpoint.matchesDeclaration(selector: SymbolSelector): Boolean =
    compilerIdentity == selector.compilerIdentity && file == selector.file && range == selector.range
