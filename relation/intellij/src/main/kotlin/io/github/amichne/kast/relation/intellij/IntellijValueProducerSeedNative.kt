@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueInvocationSelection
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
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
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
        if (!request.budget.resources.permitsWork(SEED_WORK_UNITS))
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
                is Refinement.Rejected -> return admitted.failure
                is Refinement.Refined -> admitted.value
            }
        val endpoint =
            when (val proof = proveInvocation(expression, enclosing, callable, request.expectedCallable)) {
                is Refinement.Rejected -> return ValueProducerSeedRead.Rejected(proof.failure)
                is Refinement.Refined -> proof.value.endpoint
            }
        return detachedSeed(request, endpoint, expression, started)
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
                adapter.prepare(
                    project,
                    RelationRequest.start(
                        selector,
                        RelationMeaning.References,
                        budget,
                        if (declaration == NativeSeedDeclaration.ENCLOSING) RelationSearchBoundary.WORKSPACE_EXPANSION
                        else RelationSearchBoundary.RETAINED_SUBJECT,
                    ),
                    model,
                )
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
            val declaration =
                when (val symbol = resolved.signature.symbol) {
                    is KaNamedFunctionSymbol -> symbol.psi
                    is KaConstructorSymbol -> symbol.psi
                    else -> null
                }
                    as? PsiNamedElement
                    ?: return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
            if (declaration !== callable.owner)
                return@observedAnalyze Refinement.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH)
            projectDeclaration(enclosing, declaration, expected, ValueProducerSeedRejection.CALLABLE_MISMATCH)
        }

    private fun detachedSeed(
        request: ValueProducerSeedRequest,
        endpoint: RelationEndpoint,
        expression: NativeSeedExpression,
        started: Long,
    ): ValueProducerSeedRead {
        if (elapsedBudgetReached(started, request.budget))
            return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.TIME_LIMIT_REACHED)
        val invocation =
            when (
                val result =
                    ValueInvocation.fromCompiler(
                        enclosing = RelationEndpoint.subject(request.enclosing),
                        range = sourceValueRange(expression.expression),
                        callable = endpoint,
                        executionOwner = expression.executionOwner,
                    )
            ) {
                is Refinement.Rejected ->
                    return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
                is Refinement.Refined -> result.value
            }
        val site = invocation.resultSite()
        if (!request.budget.permitsReturnedBytes(site.retainedBytes))
            return ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.BYTE_LIMIT_REACHED)
        val selection =
            when (val selected = ValueInvocationSelection.fromCompiler(invocation, request.anchor)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return ValueProducerSeedRead.ContractRejected(selected.failure)
            }
        return when (val result = ValueProducerSeed.fromCompiler(request, site, selection)) {
            is Refinement.Refined ->
                ValueProducerSeedRead.Seeded(result.value, workCount(SEED_WORK_UNITS + expression.captureWork.value))
            is Refinement.Rejected -> ValueProducerSeedRead.ContractRejected(result.failure)
        }
    }

    private fun revalidateDeclaration(
        project: Project,
        selector: SymbolSelector,
        budget: RelationBudget,
        model: WorkspaceSearchScopeModelCompilation,
    ): ValueModelDeclarationRead {
        if (!budget.resources.permitsWork(MODEL_REVALIDATION_WORK_UNITS))
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

private data class NativeSeedExpression(
    val expression: KtExpression,
    val call: KtCallElement,
    val executionOwner: ValueExecutionOwner,
    val captureWork: RelationWorkCount,
)

private fun exactSeedExpression(
    enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    request: ValueProducerSeedRequest,
): Refinement<NativeSeedExpression, ValueProducerSeedRead.Rejected> {
    val expression =
        enclosing.owner.exactValueElement(request.anchor)
            ?: return Refinement.Rejected(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH))
    val call =
        when (expression) {
            is KtCallElement -> expression
            is KtQualifiedExpression -> expression.selectorExpression as? KtCallElement
            else -> null
        }
            ?: return Refinement.Rejected(
                ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
            )
    val canonical =
        call.valueInvocationExpression() as? KtExpression
            ?: return Refinement.Rejected(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH))
    if (expression !== call && expression !== canonical)
        return Refinement.Rejected(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH))
    return when (
        val execution =
            nativeValueInitialExecution(
                element = canonical,
                host = enclosing.owner,
                enclosing = RelationEndpoint.subject(request.enclosing),
                scope = enclosing.scope,
                projection = enclosing.projection,
                reservedWork = SEED_WORK_UNITS,
            )
    ) {
        is Refinement.Refined ->
            Refinement.Refined(
                NativeSeedExpression(canonical, call, execution.value.owner, execution.value.examinedWork)
            )
        is Refinement.Rejected -> Refinement.Rejected(execution.failure.seedRejection(request))
    }
}

private fun NativeInitialValueFailure.seedRejection(request: ValueProducerSeedRequest): ValueProducerSeedRead.Rejected =
    ValueProducerSeedRead.Rejected(
        producerCause(),
        io.github.amichne.kast.relation.contract.ValueProducerSeedDiagnostic.InitialExecution(
            RelationEndpoint.subject(request.enclosing),
            request.anchor,
            when (this) {
                is NativeInitialValueFailure.Native ->
                    io.github.amichne.kast.relation.contract.ValueProducerExecutionFailure.Native(cause)
                is NativeInitialValueFailure.Unsupported ->
                    io.github.amichne.kast.relation.contract.ValueProducerExecutionFailure.Unsupported(
                        cause,
                        callbackFailures,
                    )
            },
        ),
    )

private fun NativeInitialValueFailure.producerCause(): ValueProducerSeedRejection =
    when (this) {
        is NativeInitialValueFailure.Native -> cause.seedCause()
        is NativeInitialValueFailure.Unsupported ->
            when (cause) {
                ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> ValueProducerSeedRejection.WORK_LIMIT_REACHED
                ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> ValueProducerSeedRejection.TIME_LIMIT_REACHED
                ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> ValueProducerSeedRejection.BYTE_LIMIT_REACHED
                ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED -> ValueProducerSeedRejection.GRANT_TOO_SMALL
                ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED -> ValueProducerSeedRejection.FINALLY_UNSUPPORTED
                ValueFlowUnsupportedCause.ABRUPT_COMPLETION -> ValueProducerSeedRejection.ABRUPT_COMPLETION
                ValueFlowUnsupportedCause.EXTERNAL_CALL -> ValueProducerSeedRejection.EXTERNAL_CALL
                ValueFlowUnsupportedCause.UNMODELED_CALL -> ValueProducerSeedRejection.UNMODELED_CALL
                ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW -> ValueProducerSeedRejection.MUTABLE_CONTROL_FLOW
                ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION -> ValueProducerSeedRejection.UNSUPPORTED_EXPRESSION
                ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE -> ValueProducerSeedRejection.UNRESOLVED_REFERENCE
                ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY -> ValueProducerSeedRejection.UNSUPPORTED_PROPERTY
                ValueFlowUnsupportedCause.UNSUPPORTED_RETURN -> ValueProducerSeedRejection.UNSUPPORTED_RETURN
                ValueFlowUnsupportedCause.NESTED_EXECUTION -> ValueProducerSeedRejection.NESTED_EXECUTION
                ValueFlowUnsupportedCause.OUTSIDE_DOMAIN -> ValueProducerSeedRejection.OUTSIDE_DOMAIN
                ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED ->
                    ValueProducerSeedRejection.ACCESS_PATH_DEPTH_EXCEEDED
                ValueFlowUnsupportedCause.CALL_CONTEXT_DEPTH_EXCEEDED ->
                    ValueProducerSeedRejection.CALL_CONTEXT_DEPTH_EXCEEDED
                ValueFlowUnsupportedCause.RECURSIVE_SOURCE_CALL -> ValueProducerSeedRejection.RECURSIVE_SOURCE_CALL
            }
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
    !budget.resources.hasTimeRemaining(
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0L))
    )

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
        ValueFlowRejection.OWNER_UNAVAILABLE -> ValueProducerSeedRejection.OWNER_UNAVAILABLE
        ValueFlowRejection.NESTED_EXECUTION -> ValueProducerSeedRejection.NESTED_EXECUTION
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
