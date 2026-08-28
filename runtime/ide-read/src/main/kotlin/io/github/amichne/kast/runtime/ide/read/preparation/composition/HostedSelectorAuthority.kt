package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Closed endpoint-scoped candidate-token issuance. */
internal sealed interface HostedCandidateIssuance {
    data class Issued(val token: ProtocolText) : HostedCandidateIssuance
    data object Rejected : HostedCandidateIssuance
}

/** Closed endpoint-scoped candidate-token lookup. */
internal sealed interface HostedCandidateLookup {
    data class Found(val selection: SymbolDiscoverySelection) : HostedCandidateLookup
    data object Missing : HostedCandidateLookup
}

/** Closed endpoint-scoped exact-token issuance. */
internal sealed interface HostedExactIssuance {
    data class Issued(val token: ProtocolText) : HostedExactIssuance
    data object Rejected : HostedExactIssuance
}

/** Closed endpoint-scoped exact-token lookup. */
internal sealed interface HostedExactLookup {
    data class Found(val selector: SymbolSelector) : HostedExactLookup
    data object Missing : HostedExactLookup
}

private sealed interface HostedSelectorSequence {
    data class Available(val next: Long) : HostedSelectorSequence
    data object Exhausted : HostedSelectorSequence
}

/**
 * Sole endpoint-scoped authority over detached candidate and exact selector capabilities.
 *
 * Tokens are deterministic for one operation sequence and carry no authority without lookup in
 * this lifecycle owner. The maps retain only host-neutral strong domain values; Project, VFS, PSI,
 * index, scope, and compiler objects cannot enter them.
 */
internal class HostedSelectorAuthority {
    private var sequence: HostedSelectorSequence = HostedSelectorSequence.Available(1)
    private val candidates = linkedMapOf<ProtocolText, SymbolDiscoverySelection>()
    private val exact = linkedMapOf<ProtocolText, SymbolSelector>()

    /**
     * Proof transition: `(SymbolDiscoveryBatch, Int) -> HostedCandidateIssuance`.
     *
     * Establishes one batch-owned declaration selection retained behind a bounded protocol token.
     * Non-declarations, invalid ordinals, exhausted identity, and token rejection remain closed.
     * Raw token text leaves only at [ProtocolText.parse].
     */
    @Synchronized
    fun issueCandidate(
        batch: SymbolDiscoveryBatch,
        ordinal: Int,
    ): HostedCandidateIssuance {
        if (batch.candidates.getOrNull(ordinal)?.location !is
            SymbolDiscoveryCandidateLocation.Declaration
        ) {
            return HostedCandidateIssuance.Rejected
        }
        val selection = when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return HostedCandidateIssuance.Rejected
        }
        val token = nextToken("candidate", selection.lease.generation.value)
            ?: return HostedCandidateIssuance.Rejected
        candidates[token] = selection
        return HostedCandidateIssuance.Issued(token)
    }

    /** Preserves candidate authority only for a token issued by this endpoint generation. */
    @Synchronized
    fun candidate(token: ProtocolText): HostedCandidateLookup =
        candidates[token]?.let(HostedCandidateLookup::Found) ?: HostedCandidateLookup.Missing

    /**
     * Proof transition: `SymbolSelector -> HostedExactIssuance`.
     *
     * Retains one compiler-grounded exact selector behind an endpoint-scoped bounded token.
     * Exhaustion and token admission failure remain closed.
     */
    @Synchronized
    fun issueExact(selector: SymbolSelector): HostedExactIssuance {
        val token = nextToken("exact", selector.lease.generation.value)
            ?: return HostedExactIssuance.Rejected
        exact[token] = selector
        return HostedExactIssuance.Issued(token)
    }

    /** Preserves exact authority only for a token issued by this endpoint generation. */
    @Synchronized
    fun exact(token: ProtocolText): HostedExactLookup =
        exact[token]?.let(HostedExactLookup::Found) ?: HostedExactLookup.Missing

    private fun nextToken(family: String, generation: Long): ProtocolText? {
        val current = sequence as? HostedSelectorSequence.Available ?: return null
        sequence = if (current.next == Long.MAX_VALUE) {
            HostedSelectorSequence.Exhausted
        } else {
            HostedSelectorSequence.Available(current.next + 1)
        }
        return when (
            val parsed = ProtocolText.parse("$family:v1:$generation:${current.next}")
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> null
        }
    }
}
