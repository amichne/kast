package io.github.amichne.kast.relation.contract

/** Optional retained fact lookup. A miss authorizes fresh extraction, never a complete empty semantic answer. */
sealed interface CallbackSummaryCacheLookup {
    data object Miss : CallbackSummaryCacheLookup

    data class Found(val summary: CallbackParameterSummary) : CallbackSummaryCacheLookup
}

/** The host proves dependency continuity before invoking compiler-owned endpoint readmission. */
interface CallbackSummaryCachePort {
    val namedRelations: NamedRelationCachePort
        get() = NamedRelationCachePort.Disabled

    val suppliers: CallbackSupplierCachePort
        get() = CallbackSupplierCachePort.Disabled

    fun find(
        formal: CallbackParameterIdentity,
        readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
    ): CallbackSummaryCacheLookup

    /** Optional publication owns its rejection observations; it cannot change the semantic result. */
    fun retain(summary: CallbackParameterSummary)

    /** Called after request retention and supplier-specific semantic admission both succeed. */
    fun admitted(summary: CallbackParameterSummary)

    /** Called inside the native attempt's finally block, including cancellation and preemption. */
    fun finishNativeRead() = Unit

    data object Disabled : CallbackSummaryCachePort {
        override fun find(
            formal: CallbackParameterIdentity,
            readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
        ) = CallbackSummaryCacheLookup.Miss

        override fun retain(summary: CallbackParameterSummary) = Unit

        override fun admitted(summary: CallbackParameterSummary) = Unit
    }
}

/** Invoked within the caller's native read; all work, including preemption, is debited through charge. */
fun interface CallbackSummaryCachePreparationPort {
    fun prepare(
        request: RelationRequest,
        remaining: io.github.amichne.kast.kernel.ResourceBudget,
        currentBudget:
            () -> io.github.amichne.kast.kernel.Refinement<
                    io.github.amichne.kast.kernel.ResourceBudget,
                    io.github.amichne.kast.kernel.PositiveLimitFailure,
                >,
        charge: (RelationWorkCount) -> Unit,
    ): CallbackSummaryCachePort

    data object Disabled : CallbackSummaryCachePreparationPort {
        override fun prepare(
            request: RelationRequest,
            remaining: io.github.amichne.kast.kernel.ResourceBudget,
            currentBudget:
                () -> io.github.amichne.kast.kernel.Refinement<
                        io.github.amichne.kast.kernel.ResourceBudget,
                        io.github.amichne.kast.kernel.PositiveLimitFailure,
                    >,
            charge: (RelationWorkCount) -> Unit,
        ) = CallbackSummaryCachePort.Disabled
    }
}
