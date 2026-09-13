package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedSymbolHandle
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.security.MessageDigest

/** Detached token transport. Lookup never confers semantic authority; the canonical codec still validates it. */
interface QueryReferenceTransport {
    fun issue(canonical: ProtocolText): ProtocolText

    fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText>

    data object Inline : QueryReferenceTransport {
        override fun issue(canonical: ProtocolText): ProtocolText = canonical

        override fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> =
            CanonicalSelectorDecoding.Decoded(token)
    }
}

/** Deterministic transport identity for an already encoded exact or candidate selector; no source hashing occurs. */
fun compactSymbolReference(canonical: ProtocolText): HostedSymbolHandle {
    val prefix = canonical.value.substringBefore(':')
    val digest =
        MessageDigest.getInstance("SHA-256").digest(canonical.value.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
        }
    val text = (ProtocolText.parse("$prefix:v4:$digest") as Refinement.Refined).value
    return (HostedSymbolHandle.parse(text) as Refinement.Refined).value
}

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
