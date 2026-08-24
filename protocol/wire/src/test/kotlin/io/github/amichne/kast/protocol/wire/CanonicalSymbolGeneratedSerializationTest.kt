package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalSymbolGeneratedSerializationTest {
    @Test
    fun `generated documents preserve canonical symbol wire shapes`() {
        val request = SymbolDiscoverRequest(
            SymbolDiscoverTargetDocument.Name(
                text("Controller"),
                SymbolNameKindDocument.SYMBOL,
                SymbolDiscoveryMatchDocument.EXACT_NAME,
            ),
            count(25),
        )
        assertEquals(
            """{"target":{"type":"name","query":"Controller","kind":"symbol","match":"exact-name"},"limit":25}""",
            CanonicalSymbolSerializers.discoverRequest.encode(request, WireValueRole.REQUEST).json(),
        )

        val unavailable = SymbolDescribeResult(
            SymbolDocument(
                selector = text("exact:v1:sample"),
                kind = SymbolKindDocument.TYPE_ALIAS,
                name = text("Sample"),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Unavailable,
                file = text("src/Sample.kt"),
                range = range(4, 10),
            ),
        )
        assertEquals(
            """{"symbol":{"selector":"exact:v1:sample","kind":"type-alias","name":"Sample","qualifiedIdentity":null,"file":"src/Sample.kt","range":{"startInclusive":4,"endExclusive":10}}}""",
            CanonicalSymbolSerializers.describeResult.encode(unavailable, WireValueRole.RESULT).json(),
        )
    }

    @Test
    fun `generated request rejects missing unknown and unrecognized target content`() {
        val malformed = listOf(
            """{"target":{"type":"name","query":"Controller","kind":"symbol","match":"exact-name"}}""",
            """{"target":{"type":"name","query":"Controller","kind":"symbol","match":"exact-name"},"limit":25,"extra":true}""",
            """{"target":{"type":"unknown","query":"Controller"},"limit":25}""",
        )

        malformed.forEach { document ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.REQUEST)),
                CanonicalSymbolSerializers.discoverRequest.decode(
                    wireJson.parseToJsonElement(document),
                    WireValueRole.REQUEST,
                ),
            )
        }
    }

    @Test
    fun `generated symbol result rejects missing null marker and invalid range`() {
        val malformed = listOf(
            """{"symbol":{"selector":"exact:v1:sample","kind":"function","name":"sample","file":"src/Sample.kt","range":{"startInclusive":4,"endExclusive":10}}}""",
            """{"symbol":{"selector":"exact:v1:sample","kind":"function","name":"sample","qualifiedIdentity":null,"file":"src/Sample.kt","range":{"startInclusive":10,"endExclusive":4}}}""",
        )

        malformed.forEach { document ->
            assertEquals(
                WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.RESULT)),
                CanonicalSymbolSerializers.describeResult.decode(
                    wireJson.parseToJsonElement(document),
                    WireValueRole.RESULT,
                ),
            )
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

    private fun WireValueEncoding.json(): String = when (this) {
        is WireValueEncoding.Encoded -> value.toString()
        is WireValueEncoding.Rejected -> error("Expected encoded value, got $failure")
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
