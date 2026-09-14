package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedSymbolHandle
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.security.MessageDigest
import java.util.Base64

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
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(canonical.value.toByteArray(Charsets.UTF_8)).copyOf(16)
            )
    val text = (ProtocolText.parse("$prefix:v5:$digest") as Refinement.Refined).value
    return (HostedSymbolHandle.parse(text) as Refinement.Refined).value
}
