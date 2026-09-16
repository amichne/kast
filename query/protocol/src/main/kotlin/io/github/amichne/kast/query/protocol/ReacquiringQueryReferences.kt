package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** A request-local fresh read capability. Strict restoration and continuation validation stay delegated. */
fun interface ExactReferenceReacquisition {
    suspend fun acquire(token: ProtocolText, current: SemanticReadAuthority): CanonicalSelectorDecoding<SymbolSelector>
}

class ReacquiringQueryReferences(
    private val strict: QueryReferenceAuthority,
    private val reacquisition: ExactReferenceReacquisition,
    private val remaining:
        (io.github.amichne.kast.kernel.ResourceBudget) -> io.github.amichne.kast.kernel.Refinement<
                io.github.amichne.kast.kernel.ResourceBudget,
                ReadReacquisitionBudgetFailure,
            > =
        {
            io.github.amichne.kast.kernel.Refinement.Refined(it)
        },
) : QueryReferenceAuthority by strict {
    private data class Key(val token: ProtocolText, val current: SemanticReadAuthority)

    private val attempts = mutableMapOf<Key, CanonicalSelectorDecoding<SymbolSelector>>()

    private val refreshed = mutableListOf<io.github.amichne.kast.protocol.contract.ReadReferenceAcquisition>()
    private var report: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null

    override fun readAcquisitions() = report

    override fun remainingReadBudget(budget: io.github.amichne.kast.kernel.ResourceBudget) = remaining(budget)

    override suspend fun acquireExact(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<SymbolSelector> {
        val restored = strict.restoreExact(token, current)
        if (restored !is CanonicalSelectorDecoding.Rejected || restored.failure !in RECOVERABLE) return restored
        val key = Key(token, current)
        attempts[key]?.let {
            return it
        }
        if (attempts.size >= MAX_ACQUISITIONS)
            return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY)
        val acquired = reacquisition.acquire(token, current)
        val result =
            when (acquired) {
                is CanonicalSelectorDecoding.Rejected -> acquired
                is CanonicalSelectorDecoding.Decoded -> issueAcquired(token, current, acquired)
            }
        attempts[key] = result
        return result
    }

    private fun issueAcquired(
        token: ProtocolText,
        current: SemanticReadAuthority,
        acquired: CanonicalSelectorDecoding.Decoded<SymbolSelector>,
    ): CanonicalSelectorDecoding<SymbolSelector> {
        if (acquired.value.lease !== current)
            return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED)
        val issued =
            when (val result = strict.issueExact(acquired.value)) {
                is ExactSelectorIssuance.Rejected ->
                    return CanonicalSelectorDecoding.Rejected(
                        CanonicalSelectorDecodingFailure.REVALIDATION_CAPTURE_UNAVAILABLE
                    )
                is ExactSelectorIssuance.Issued -> result.selector
            }
        val entries = refreshed + io.github.amichne.kast.protocol.contract.ReadReferenceAcquisition(token, issued)
        return when (val admitted = io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions.admit(entries)) {
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY)
            is io.github.amichne.kast.kernel.Refinement.Refined -> {
                refreshed.add(entries.last())
                report = admitted.value
                acquired
            }
        }
    }

    private companion object {
        const val MAX_ACQUISITIONS = 256
        val RECOVERABLE =
            setOf(CanonicalSelectorDecodingFailure.UNAVAILABLE, CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
    }
}

fun io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.decodingFailure():
    CanonicalSelectorDecodingFailure =
    when (this) {
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.WRONG_KIND ->
            CanonicalSelectorDecodingFailure.REVALIDATION_WRONG_KIND
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.UNRETAINED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_UNRETAINED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.EXPIRED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_EXPIRED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.CAPACITY ->
            CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.WORK_LIMIT_REACHED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.TIME_LIMIT_REACHED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_TIME_LIMIT_REACHED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.RETIRED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_RETIRED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.CAPTURE_UNAVAILABLE ->
            CanonicalSelectorDecodingFailure.REVALIDATION_CAPTURE_UNAVAILABLE
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.WORKSPACE_MISMATCH ->
            CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_MISMATCH
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.OWNER_MISMATCH ->
            CanonicalSelectorDecodingFailure.REVALIDATION_OWNER_MISMATCH
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.WORKSPACE_NOT_READY ->
            CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_NOT_READY
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.BASIS_MOVED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.CONTENT_CHANGED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.CONTENT_UNCOMMITTED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_UNCOMMITTED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.SCOPE_REJECTED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_SCOPE_REJECTED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.DECLARATION_MISSING ->
            CanonicalSelectorDecodingFailure.REVALIDATION_DECLARATION_MISSING
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.UNSUPPORTED_DECLARATION ->
            CanonicalSelectorDecodingFailure.REVALIDATION_UNSUPPORTED_DECLARATION
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.AMBIGUOUS ->
            CanonicalSelectorDecodingFailure.REVALIDATION_AMBIGUOUS
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.COMPILER_IDENTITY_CHANGED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED
        io.github.amichne.kast.symbol.contract.ExactRevalidationRejection.COMPILER_UNAVAILABLE ->
            CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_UNAVAILABLE
    }
