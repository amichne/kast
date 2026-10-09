@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Reuses the exact scope/projector and current-owner boundary used by the one-hop value adapter. */
internal class IntellijValueProducerSeedNative(private val adapter: IntellijValueFlowCompilerAdapter) {
    suspend fun seed(
        project: Project,
        current: SemanticReadAuthority,
        request: ValueProducerSeedRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueProducerSeedRead =
        adapter.nativeBoundary(
            project,
            current,
            request.enclosing.lease,
            { ValueProducerSeedRead.Rejected(it.seedCause()) },
            IntellijReadStage.VALUE_PRODUCER_SEED,
        ) {
            produceSeed(project, request, model)
        }

    suspend fun revalidate(
        project: Project,
        current: SemanticReadAuthority,
        selector: SymbolSelector,
        budget: RelationBudget,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelDeclarationRead =
        adapter.nativeBoundary(
            project,
            current,
            selector.lease,
            { ValueModelDeclarationRead.Rejected(it.seedCause()) },
            IntellijReadStage.VALUE_MODEL_ADMISSION,
        ) {
            revalidateDeclaration(project, selector, budget, model)
        }

    private fun produceSeed(
        project: Project,
        request: ValueProducerSeedRequest,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueProducerSeedRead {
        if (request.budget.resources.workUnitLimit.value < SEED_WORK_UNITS)
            return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.WORK_LIMIT_REACHED)
        val started = System.nanoTime()
        // P is selected under its retained declaration authority; D limits later expansion.
        val enclosing =
            when (
                val prepared =
                    prepareDeclaration(
                        project,
                        request.enclosing,
                        request.budget,
                        model,
                        NativeSeedDeclaration.ENCLOSING,
                    )
            ) {
                is Refinement.Rejected -> return ValueProducerSeedRead.Rejected(prepared.failure)
                is Refinement.Refined -> prepared.value
            }
        val callable =
            when (
                val prepared =
                    prepareDeclaration(
                        project,
                        request.expectedCallable,
                        request.budget,
                        model,
                        NativeSeedDeclaration.CALLABLE,
                    )
            ) {
                is Refinement.Rejected -> return ValueProducerSeedRead.Rejected(prepared.failure)
                is Refinement.Refined -> prepared.value
            }
        val expression =
            when (val admitted = exactSeedExpression(enclosing, request)) {
                is Refinement.Rejected -> return ValueProducerSeedRead.Rejected(admitted.failure)
                is Refinement.Refined -> admitted.value
            }
        val endpoint =
            when (val proof = proveInvocation(expression, enclosing, callable, request.expectedCallable)) {
                is Refinement.Rejected -> return ValueProducerSeedRead.Rejected(proof.failure)
                is Refinement.Refined -> proof.value.endpoint
            }
        return detachedSeed(request, endpoint, started)
    }

    private fun prepareDeclaration(
        project: Project,
        selector: SymbolSelector,
        budget: RelationBudget,
        model: WorkspaceSearchScopeModelCompilation,
        declaration: NativeSeedDeclaration,
    ): Refinement<IntellijValueFlowCompilerAdapter.Prepared.Ready, ValueProducerSeedRejection> =
        when (
            val result =
                adapter.prepare(project, RelationRequest.start(selector, RelationMeaning.References, budget), model)
        ) {
            is IntellijValueFlowCompilerAdapter.Prepared.Ready -> Refinement.Refined(result)
            is IntellijValueFlowCompilerAdapter.Prepared.Rejected ->
                Refinement.Rejected(result.cause.seedCause(declaration))
        }

    private fun proveInvocation(
        expression: NativeSeedExpression,
        enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
        callable: IntellijValueFlowCompilerAdapter.Prepared.Ready,
        expected: SymbolSelector,
    ): Refinement<RevalidatedRelationEndpoint, ValueProducerSeedRejection> =
        adapter.observation.observedAnalyze(expression.expression) {
            val type = expression.expression.expressionType
            if (type == null || type is KaErrorType)
                return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
            val resolved =
                expression.call.resolveCall()
                    ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
            val symbol =
                resolved.signature.symbol as? KaNamedFunctionSymbol
                    ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
            val declaration =
                symbol.psi as? PsiNamedElement
                    ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
            if (declaration !== callable.owner)
                return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH)
            projectDeclaration(enclosing, declaration, expected, ValueProducerSeedRejection.CALLABLE_MISMATCH)
        }

    private fun detachedSeed(
        request: ValueProducerSeedRequest,
        endpoint: RelationEndpoint,
        started: Long,
    ): ValueProducerSeedRead {
        if (elapsedBudgetReached(started, request.budget))
            return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.TIME_LIMIT_REACHED)
        val invocation =
            when (
                val result =
                    ValueInvocation.fromCompiler(RelationEndpoint.subject(request.enclosing), request.anchor, endpoint)
            ) {
                is Refinement.Rejected ->
                    return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
                is Refinement.Refined -> result.value
            }
        val site = invocation.resultSite()
        if (site.retainedBytes > request.budget.returnedBytes.value)
            return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.BYTE_LIMIT_REACHED)
        return when (val result = ValueProducerSeed.fromCompiler(request, site, invocation)) {
            is Refinement.Refined -> ValueProducerSeedRead.Seeded(result.value, workCount(SEED_WORK_UNITS))
            is Refinement.Rejected -> ValueProducerSeedRead.ContractRejected(result.failure)
        }
    }

    private fun revalidateDeclaration(
        project: Project,
        selector: SymbolSelector,
        budget: RelationBudget,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelDeclarationRead {
        if (budget.resources.workUnitLimit.value < MODEL_REVALIDATION_WORK_UNITS)
            return ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.WORK_LIMIT_REACHED)
        val started = System.nanoTime()
        val native =
            when (val prepared = prepareDeclaration(project, selector, budget, model, NativeSeedDeclaration.CALLABLE)) {
                is Refinement.Rejected -> return ValueModelDeclarationRead.Rejected(prepared.failure)
                is Refinement.Refined -> prepared.value
            }
        val declaration =
            native.owner as? PsiNamedElement
                ?: return ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.STALE_CALLABLE)
        // Projection precedes the time check; full selector identity is revalidated only after it.
        val evidence =
            when (val result = native.projection.project(declaration)) {
                is IntellijRelationDeclarationProjection.Projected -> result.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
            }
        if (elapsedBudgetReached(started, budget))
            return ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.TIME_LIMIT_REACHED)
        return when (val result = RevalidatedRelationEndpoint.validate(RelationEndpoint.subject(selector), evidence)) {
            is Refinement.Refined ->
                ValueModelDeclarationRead.Revalidated(result.value, workCount(MODEL_REVALIDATION_WORK_UNITS))
            is Refinement.Rejected -> ValueModelDeclarationRead.Rejected(ValueProducerSeedRejection.STALE_CALLABLE)
        }
    }
}

private data class NativeSeedExpression(val expression: KtExpression, val call: KtCallElement)

private fun exactSeedExpression(
    enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    request: ValueProducerSeedRequest,
): Refinement<NativeSeedExpression, ValueProducerSeedRejection> {
    val expression =
        enclosing.owner.exactValueElement(request.anchor)
            ?: return Refinement.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
    if (valueNativeOwnership(expression, enclosing.owner) != NativeValueOwnership.ADMITTED)
        return Refinement.Rejected(ValueProducerSeedRejection.OWNER_MISMATCH)
    val call =
        when (expression) {
            is KtCallElement -> expression
            is KtQualifiedExpression -> expression.selectorExpression as? KtCallElement
            else -> null
        } ?: return Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
    if (call.valueInvocationExpression() !== expression)
        return Refinement.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
    return Refinement.Refined(NativeSeedExpression(expression, call))
}

private fun projectDeclaration(
    native: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    declaration: PsiNamedElement,
    selector: SymbolSelector,
    mismatch: ValueProducerSeedRejection,
): Refinement<RevalidatedRelationEndpoint, ValueProducerSeedRejection> =
    when (val projected = native.projection.project(declaration)) {
        is IntellijRelationDeclarationProjection.Projected ->
            when (
                val proof = RevalidatedRelationEndpoint.validate(RelationEndpoint.subject(selector), projected.evidence)
            ) {
                is Refinement.Refined -> proof
                is Refinement.Rejected -> Refinement.Rejected(mismatch)
            }
        IntellijRelationDeclarationProjection.Unsupported ->
            Refinement.Rejected(ValueProducerSeedRejection.UNRESOLVED_INVOCATION)
    }

private fun elapsedBudgetReached(started: Long, budget: RelationBudget): Boolean =
    System.nanoTime() - started >=
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget.resources.elapsedTimeLimit.value)

private const val SEED_WORK_UNITS = 3L
private const val MODEL_REVALIDATION_WORK_UNITS = 1L

private enum class NativeSeedDeclaration {
    ENCLOSING,
    CALLABLE,
}

private fun ValueFlowRejection.seedCause(
    declaration: NativeSeedDeclaration = NativeSeedDeclaration.ENCLOSING
): ValueProducerSeedRejection =
    when (this) {
        ValueFlowRejection.STALE_SITE ->
            when (declaration) {
                NativeSeedDeclaration.ENCLOSING -> ValueProducerSeedRejection.STALE_ENCLOSING
                NativeSeedDeclaration.CALLABLE -> ValueProducerSeedRejection.STALE_CALLABLE
            }
        ValueFlowRejection.OUTSIDE_DOMAIN -> ValueProducerSeedRejection.OUTSIDE_DOMAIN
        ValueFlowRejection.OWNER_UNAVAILABLE,
        ValueFlowRejection.NESTED_EXECUTION -> ValueProducerSeedRejection.OWNER_MISMATCH
        ValueFlowRejection.AUTHORITY_MOVED -> ValueProducerSeedRejection.AUTHORITY_MOVED
        ValueFlowRejection.UNSUPPORTED_SEED -> ValueProducerSeedRejection.UNSUPPORTED_INVOCATION
        ValueFlowRejection.UNRESOLVED_SEED -> ValueProducerSeedRejection.UNRESOLVED_INVOCATION
        ValueFlowRejection.NATIVE_UNAVAILABLE -> ValueProducerSeedRejection.NATIVE_UNAVAILABLE
        ValueFlowRejection.GRANT_TOO_SMALL -> ValueProducerSeedRejection.GRANT_TOO_SMALL
    }

private fun workCount(count: Long): RelationWorkCount =
    when (val refined = RelationWorkCount.parse(count)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> error("Native stage count is nonnegative")
    }
