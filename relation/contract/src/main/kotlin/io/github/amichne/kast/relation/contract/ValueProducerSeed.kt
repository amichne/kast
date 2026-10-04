package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Syntax input retains two exact declarations; only the native compiler may prove the invocation anchor. */
class ValueProducerSeedRequest
private constructor(
    val enclosing: SymbolSelector,
    val anchor: ExactDeclarationTextRange,
    val expectedCallable: SymbolSelector,
    val budget: RelationBudget,
    val boundary: RelationSearchBoundary,
) {
    companion object {
        fun create(
            enclosing: SymbolSelector,
            anchor: ExactDeclarationTextRange,
            expectedCallable: SymbolSelector,
            budget: RelationBudget,
            boundary: RelationSearchBoundary,
        ): Refinement<ValueProducerSeedRequest, ValueProducerSeedRequestFailure> =
            when {
                enclosing.lease.identity != expectedCallable.lease.identity ->
                    Refinement.Rejected(ValueProducerSeedRequestFailure.BASIS_MISMATCH)
                !enclosing.range.containsValueRange(anchor) ->
                    Refinement.Rejected(ValueProducerSeedRequestFailure.ANCHOR_OUTSIDE_ENCLOSING)
                else ->
                    Refinement.Refined(ValueProducerSeedRequest(enclosing, anchor, expectedCallable, budget, boundary))
            }
    }
}

enum class ValueProducerSeedRequestFailure {
    BASIS_MISMATCH,
    ANCHOR_OUTSIDE_ENCLOSING,
}

enum class ValueProducerSeedFailure {
    BASIS_MISMATCH,
    ENCLOSING_MISMATCH,
    CALLABLE_MISMATCH,
    ANCHOR_MISMATCH,
    ROLE_MISMATCH,
}

/**
 * Actual invocation identity P and its result site remain separate from source syntax and from the expansion domain D.
 */
class ValueProducerSeed private constructor(val site: ValueSite, val invocation: ValueInvocation) {
    companion object {
        fun fromCompiler(
            request: ValueProducerSeedRequest,
            site: ValueSite,
            invocation: ValueInvocation,
        ): Refinement<ValueProducerSeed, ValueProducerSeedFailure> =
            when {
                site.basis != request.enclosing.lease.identity || invocation.basis != site.basis ->
                    Refinement.Rejected(ValueProducerSeedFailure.BASIS_MISMATCH)
                !site.enclosing.sameDeclaration(request.enclosing) ||
                    !invocation.enclosing.sameDeclaration(request.enclosing) ->
                    Refinement.Rejected(ValueProducerSeedFailure.ENCLOSING_MISMATCH)
                !invocation.callable.sameDeclaration(request.expectedCallable) ->
                    Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH)
                site.range != request.anchor || invocation.range != request.anchor ->
                    Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH)
                site.role != ValueRole.ExpressionResult -> Refinement.Rejected(ValueProducerSeedFailure.ROLE_MISMATCH)
                else -> Refinement.Refined(ValueProducerSeed(site, invocation))
            }
    }
}

private fun RelationEndpoint.sameDeclaration(selector: SymbolSelector): Boolean =
    compilerIdentity == selector.compilerIdentity && file == selector.file && range == selector.range

enum class ValueProducerSeedRejection {
    STALE_ENCLOSING,
    STALE_CALLABLE,
    UNRESOLVED_INVOCATION,
    UNSUPPORTED_INVOCATION,
    ANCHOR_MISMATCH,
    CALLABLE_MISMATCH,
    OWNER_MISMATCH,
    AUTHORITY_MOVED,
    NATIVE_UNAVAILABLE,
    GRANT_TOO_SMALL,
    OUTSIDE_DOMAIN,
    TIME_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
}

sealed interface ValueProducerSeedRead {
    data class Seeded(val seed: ValueProducerSeed, val examinedWorkUnits: RelationWorkCount) : ValueProducerSeedRead

    data class Rejected(val cause: ValueProducerSeedRejection) : ValueProducerSeedRead

    data class ContractRejected(val cause: ValueProducerSeedFailure) : ValueProducerSeedRead
}

sealed interface ValueModelDeclarationRead {
    data class Revalidated(val declaration: RevalidatedRelationEndpoint, val examinedWorkUnits: RelationWorkCount) :
        ValueModelDeclarationRead

    data class Rejected(val cause: ValueProducerSeedRejection) : ValueModelDeclarationRead
}

/** Native effects use the same admitted semantic context and grants as query execution. No live handles escape. */
interface ValueProducerSeedCompilerPort {
    suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead

    suspend fun revalidate(selector: SymbolSelector, budget: RelationBudget): ValueModelDeclarationRead

    suspend fun revalidateSite(request: ValueSiteRevalidationRequest): ValueModelSiteRead =
        ValueModelSiteRead.Rejected(ValueFlowRejection.NATIVE_UNAVAILABLE)
}
