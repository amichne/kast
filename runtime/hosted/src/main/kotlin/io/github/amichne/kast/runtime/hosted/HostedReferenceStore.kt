package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedSymbolHandle
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecodingFailure
import io.github.amichne.kast.query.protocol.QueryReferenceTransport
import io.github.amichne.kast.query.protocol.compactSymbolReference
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Only detached token text is retained. Epoch changes discard references which can no longer restore authority. */
@Service(Service.Level.PROJECT)
class HostedReferenceStore : Disposable {
    private val epochs = HostedEpochStore<HostedReferenceTokens> { tokens, _ -> tokens.retire() }

    fun transport(
        authority: LiveSemanticReadAuthority,
        limits: ReadLimits,
        observation: IntellijReadObservation,
    ): Refinement<QueryReferenceTransport, io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure> =
        when (val admitted = epochs.admit(authority) { HostedReferenceTokens(limits) }) {
            is Refinement.Refined -> Refinement.Refined(admitted.value.transport(observation))
            is Refinement.Rejected -> admitted
        }

    override fun dispose() = epochs.retire()
}

/** Capacity selects a valid representation; it never evicts a handle from the current epoch. */
internal class HostedReferenceTokens(
    private val limits: ReadLimits,
    private val handleOf: (ProtocolText) -> HostedSymbolHandle = ::compactSymbolReference,
) {
    private enum class Lifetime {
        ACTIVE,
        RETIRED,
    }

    private var lifetime = Lifetime.ACTIVE
    private val entries = mutableMapOf<HostedSymbolHandle, ProtocolText>()
    private var retainedBytes = 0L

    fun transport(observation: IntellijReadObservation = IntellijReadObservation.None): QueryReferenceTransport =
        object : QueryReferenceTransport {
            override fun issue(
                canonical: ProtocolText
            ): Refinement<ProtocolText, io.github.amichne.kast.query.protocol.QueryReferenceTransportFailure> =
                when (val issued = retain(canonical)) {
                    is HostedReferenceRepresentation.Handle -> {
                        observation.count(IntellijReadCounter.REFERENCE_HANDLES_ISSUED)
                        Refinement.Refined(issued.value.token)
                    }
                    is HostedReferenceRepresentation.Inline -> {
                        observation.count(
                            when (issued.reason) {
                                HostedReferenceInlineReason.CAPACITY -> IntellijReadCounter.REFERENCE_INLINE_CAPACITY
                                HostedReferenceInlineReason.COLLISION -> IntellijReadCounter.REFERENCE_INLINE_COLLISION
                            }
                        )
                        Refinement.Refined(issued.value)
                    }
                    HostedReferenceRepresentation.Unavailable ->
                        Refinement.Rejected(
                            io.github.amichne.kast.query.protocol.QueryReferenceTransportFailure.UNAVAILABLE
                        )
                }

            override fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> {
                val restored = lookup(token)
                if (token.isHostedReference()) {
                    observation.count(
                        when (restored) {
                            is CanonicalSelectorDecoding.Decoded -> IntellijReadCounter.REFERENCE_HANDLES_RESTORED
                            is CanonicalSelectorDecoding.Rejected -> IntellijReadCounter.REFERENCE_HANDLES_REJECTED
                        }
                    )
                }
                return restored
            }
        }

    @Synchronized
    private fun retain(canonical: ProtocolText): HostedReferenceRepresentation {
        if (lifetime == Lifetime.RETIRED) return HostedReferenceRepresentation.Unavailable
        val handle = handleOf(canonical)
        val text = handle.token
        if (entries[handle] == canonical) return HostedReferenceRepresentation.Handle(handle)
        val bytes =
            canonical.value.toByteArray(Charsets.UTF_8).size.toLong() + text.value.toByteArray(Charsets.UTF_8).size
        if (entries.containsKey(handle))
            return HostedReferenceRepresentation.Inline(canonical, HostedReferenceInlineReason.COLLISION)
        if (
            entries.size >= limits[ReadLimitParameter.HOST_REFERENCE_ENTRIES].value ||
                retainedBytes + bytes > limits[ReadLimitParameter.HOST_REFERENCE_BYTES].value
        ) {
            return HostedReferenceRepresentation.Inline(canonical, HostedReferenceInlineReason.CAPACITY)
        }
        entries[handle] = canonical
        retainedBytes += bytes
        return HostedReferenceRepresentation.Handle(handle)
    }

    @Synchronized
    private fun lookup(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> {
        if (lifetime == Lifetime.RETIRED)
            return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE)
        if (!token.isHostedReference()) return CanonicalSelectorDecoding.Decoded(token)
        val handle =
            when (val parsed = HostedSymbolHandle.parse(token)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE)
            }
        return entries[handle]?.let { CanonicalSelectorDecoding.Decoded(it) }
            ?: CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE)
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.RETIRED
        entries.clear()
        retainedBytes = 0
    }
}

private sealed interface HostedReferenceRepresentation {
    data object Unavailable : HostedReferenceRepresentation

    data class Handle(val value: HostedSymbolHandle) : HostedReferenceRepresentation

    data class Inline(val value: ProtocolText, val reason: HostedReferenceInlineReason) : HostedReferenceRepresentation
}

private fun ProtocolText.isHostedReference(): Boolean =
    value.startsWith("exact:v4:") ||
        value.startsWith("candidate:v4:") ||
        value.startsWith("exact:v5:") ||
        value.startsWith("candidate:v5:")

private enum class HostedReferenceInlineReason {
    CAPACITY,
    COLLISION,
}
