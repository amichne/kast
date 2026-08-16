package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal enum class CandidateSelectorIssuanceFailure {
    NON_DECLARATION_CANDIDATE,
    HANDLE_COLLISION,
}

internal sealed interface CandidateSelectorIssuance {
    data class Issued(
        val selectors: List<ProtocolText>,
    ) : CandidateSelectorIssuance

    data class Rejected(
        val failure: CandidateSelectorIssuanceFailure,
    ) : CandidateSelectorIssuance
}

internal sealed interface CandidateSelectorLookup {
    data class Found(
        val selection: SymbolDiscoverySelection,
    ) : CandidateSelectorLookup

    data object Missing : CandidateSelectorLookup
}

internal enum class ExactSelectorIssuanceFailure {
    HANDLE_COLLISION,
}

internal sealed interface ExactSelectorIssuance {
    data class Issued(
        val selector: ProtocolText,
    ) : ExactSelectorIssuance

    data class Rejected(
        val failure: ExactSelectorIssuanceFailure,
    ) : ExactSelectorIssuance
}

internal sealed interface ExactSelectorLookup {
    data class Found(
        val selector: SymbolSelector,
    ) : ExactSelectorLookup

    data object Missing : ExactSelectorLookup
}

/** Operation-specific authority retained behind opaque canonical selector documents. */
internal class CanonicalProtocolAuthority {
    private val candidates = ConcurrentHashMap<ProtocolText, SymbolDiscoverySelection>()
    private val exact = ConcurrentHashMap<ProtocolText, SymbolSelector>()

    /**
     * Proof transition: `SymbolDiscoveryBatch -> CandidateSelectorIssuance`.
     *
     * Establishes one deterministic opaque selector for every declaration selected from the exact
     * generation-bound batch. [CandidateSelectorIssuanceFailure] is the closed expected failure.
     * Raw ordinals are extracted only while applying the batch-owned selection transition.
     */
    fun issueCandidates(batch: SymbolDiscoveryBatch): CandidateSelectorIssuance {
        val issued = ArrayList<ProtocolText>(batch.candidates.size)
        batch.candidates.indices.forEach { ordinal ->
            val candidate = batch.candidates[ordinal]
            if (candidate.location !is SymbolDiscoveryCandidateLocation.Declaration) {
                return CandidateSelectorIssuance.Rejected(
                    CandidateSelectorIssuanceFailure.NON_DECLARATION_CANDIDATE,
                )
            }
            val selection = when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                    CandidateSelectorIssuanceFailure.NON_DECLARATION_CANDIDATE,
                )
            }
            val handle = candidateHandle(selection)
            val prior = candidates.putIfAbsent(handle, selection)
            if (prior != null && !prior.sameSelection(selection)) {
                return CandidateSelectorIssuance.Rejected(
                    CandidateSelectorIssuanceFailure.HANDLE_COLLISION,
                )
            }
            issued += handle
        }
        return CandidateSelectorIssuance.Issued(issued)
    }

    /**
     * Proof transition: `ProtocolText -> CandidateSelectorLookup`.
     *
     * Restores only a previously batch-issued [SymbolDiscoverySelection]. Missing or manufactured
     * documents remain the finite [CandidateSelectorLookup.Missing] state. Raw text lookup is
     * confined to this protocol authority boundary.
     */
    fun candidate(selector: ProtocolText): CandidateSelectorLookup =
        candidates[selector]
            ?.let(CandidateSelectorLookup::Found)
            ?: CandidateSelectorLookup.Missing

    /**
     * Proof transition: `SymbolSelector -> ExactSelectorIssuance`.
     *
     * Preserves the compiler-grounded selector behind its own opaque fingerprint.
     * [ExactSelectorIssuanceFailure] is the closed expected collision failure. Raw fingerprint
     * extraction occurs only at this protocol authority boundary.
     */
    fun issueExact(selector: SymbolSelector): ExactSelectorIssuance {
        val handle = exactHandle(selector)
        val prior = exact.putIfAbsent(handle, selector)
        return if (prior == null || prior.sameSelector(selector)) {
            ExactSelectorIssuance.Issued(handle)
        } else {
            ExactSelectorIssuance.Rejected(ExactSelectorIssuanceFailure.HANDLE_COLLISION)
        }
    }

    /**
     * Proof transition: `ProtocolText -> ExactSelectorLookup`.
     *
     * Restores only a selector issued by `symbol.resolve`. Missing or manufactured documents
     * remain [ExactSelectorLookup.Missing]. Raw text lookup is confined to this boundary.
     */
    fun exact(selector: ProtocolText): ExactSelectorLookup =
        exact[selector]?.let(ExactSelectorLookup::Found) ?: ExactSelectorLookup.Missing
}

private fun candidateHandle(selection: SymbolDiscoverySelection): ProtocolText {
    val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
    return protocolHandle(
        "candidate",
        listOf(
            selection.lease.workspaceRoot.value,
            selection.lease.generation.value.toString(),
            selection.candidate.name.value,
            location.file.stableValue,
            location.offset.value.toString(),
        ),
    )
}

private fun exactHandle(selector: SymbolSelector): ProtocolText =
    protocolHandle("exact", listOf(selector.fingerprint.value))

private fun protocolHandle(prefix: String, fields: List<String>): ProtocolText {
    val canonical = buildString {
        fields.forEach { field ->
            append(field.toByteArray(StandardCharsets.UTF_8).size)
            append(':')
            append(field)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return when (val parsed = ProtocolText.parse("$prefix:$digest")) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("canonical selector handle is bounded and non-blank")
    }
}

private fun SymbolDiscoverySelection.sameSelection(other: SymbolDiscoverySelection): Boolean =
    lease == other.lease && scope == other.scope && candidate == other.candidate

private fun SymbolSelector.sameSelector(other: SymbolSelector): Boolean =
    lease == other.lease &&
        scope == other.scope &&
        file == other.file &&
        range == other.range &&
        name == other.name &&
        qualifiedIdentity == other.qualifiedIdentity &&
        kind == other.kind &&
        fingerprint == other.fingerprint
