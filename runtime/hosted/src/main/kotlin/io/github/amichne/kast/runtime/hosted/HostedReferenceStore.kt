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
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Only detached token text is retained. Epoch changes discard references which can no longer restore authority. */
@Service(Service.Level.PROJECT)
class HostedReferenceStore : Disposable {
    private sealed interface State {
        data object Empty : State

        class Current(val reference: LiveSemanticReadReference, val tokens: HostedReferenceTokens) : State

        data object Disposed : State
    }

    private var state: State = State.Empty

    @Synchronized
    fun transport(
        reference: LiveSemanticReadReference,
        limits: ReadLimits,
        observation: IntellijReadObservation,
    ): QueryReferenceTransport {
        val current = state
        if (current is State.Disposed) return QueryReferenceTransport.Inline
        val tokens =
            if (current is State.Current && current.reference == reference) current.tokens
            else {
                if (current is State.Current) current.tokens.clear()
                HostedReferenceTokens(limits).also { state = State.Current(reference, it) }
            }
        return tokens.transport(observation)
    }

    @Synchronized
    override fun dispose() {
        val current = state
        if (current is State.Current) current.tokens.clear()
        state = State.Disposed
    }
}

/** Capacity selects a valid representation; it never evicts a handle from the current epoch. */
internal class HostedReferenceTokens(
    private val limits: ReadLimits,
    private val handleOf: (ProtocolText) -> HostedSymbolHandle = ::compactSymbolReference,
) {
    private val entries = mutableMapOf<HostedSymbolHandle, ProtocolText>()
    private var retainedBytes = 0L

    fun transport(observation: IntellijReadObservation = IntellijReadObservation.None): QueryReferenceTransport =
        object : QueryReferenceTransport {
            override fun issue(canonical: ProtocolText): ProtocolText =
                when (val issued = retain(canonical)) {
                    is HostedReferenceRepresentation.Handle -> {
                        observation.count(IntellijReadCounter.REFERENCE_HANDLES_ISSUED)
                        issued.value.token
                    }
                    is HostedReferenceRepresentation.Inline -> {
                        observation.count(
                            when (issued.reason) {
                                HostedReferenceInlineReason.CAPACITY -> IntellijReadCounter.REFERENCE_INLINE_CAPACITY
                                HostedReferenceInlineReason.COLLISION -> IntellijReadCounter.REFERENCE_INLINE_COLLISION
                            }
                        )
                        issued.value
                    }
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
        if (!token.isHostedReference()) return CanonicalSelectorDecoding.Decoded(token)
        val handle =
            when (val parsed = HostedSymbolHandle.parse(token)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE)
            }
        return entries[handle]?.let { CanonicalSelectorDecoding.Decoded(it) }
            ?: CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        retainedBytes = 0
    }
}

private sealed interface HostedReferenceRepresentation {
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
