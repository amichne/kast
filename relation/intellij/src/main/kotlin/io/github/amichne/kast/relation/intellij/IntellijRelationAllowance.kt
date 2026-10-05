package io.github.amichne.kast.relation.intellij

internal enum class IntellijRelationEnumerationGrant {
    READY,
    UNAVAILABLE,
    TIME_LIMIT_REACHED,
}

/** One invocation's accounting survives platform read-action re-entry; its facts remain attempt-local. */
internal class IntellijRelationAllowance(private val clockNanoseconds: () -> Long) {
    val startedAt: Long = clockNanoseconds()
    var examined: Long = 0L
        private set

    var nativeCandidates: Int = 0
        private set

    fun examine() {
        examined += 1L
    }

    fun collectCandidate() {
        nativeCandidates += 1
    }

    fun admitCandidate(limits: io.github.amichne.kast.kernel.ReadLimits): IntellijRelationProviderEnumerationAdmission =
        if (nativeCandidates >= limits[io.github.amichne.kast.kernel.ReadLimitParameter.RELATION_CANDIDATES].value)
            IntellijRelationProviderEnumerationAdmission.HALTED
        else {
            collectCandidate()
            IntellijRelationProviderEnumerationAdmission.READY
        }

    fun elapsedLimitReached(resources: io.github.amichne.kast.kernel.ResourceBudget): Boolean =
        (clockNanoseconds() - startedAt).coerceAtLeast(0L) >= resources.elapsedTimeLimit.value * NANOS_PER_MILLISECOND

    fun admitEnumeration(
        state: IntellijRelationCollectionState,
        resources: io.github.amichne.kast.kernel.ResourceBudget,
    ): IntellijRelationEnumerationGrant =
        when (state) {
            IntellijRelationCollectionState.COLLECTING ->
                if (elapsedLimitReached(resources)) IntellijRelationEnumerationGrant.TIME_LIMIT_REACHED
                else IntellijRelationEnumerationGrant.READY
            IntellijRelationCollectionState.HALTED,
            IntellijRelationCollectionState.ENUMERATION_LIMIT,
            IntellijRelationCollectionState.CONTRACT_REJECTED -> IntellijRelationEnumerationGrant.UNAVAILABLE
        }

    /** Nested callback proof shares the original native attempt's allowance. */
    fun admitCallbackWork(resources: io.github.amichne.kast.kernel.ResourceBudget): CallbackWorkAdmission =
        when {
            elapsedLimitReached(resources) -> CallbackWorkAdmission.TIME_LIMIT_REACHED
            examined >= resources.workUnitLimit.value -> CallbackWorkAdmission.WORK_LIMIT_REACHED
            else -> {
                examine()
                CallbackWorkAdmission.READY
            }
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
