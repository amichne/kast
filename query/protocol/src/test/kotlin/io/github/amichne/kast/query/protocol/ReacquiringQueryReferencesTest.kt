package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReacquiringQueryReferencesTest {
    @Test
    fun `failed acquisition is cached and never issues a handle from another authority`() = runTest {
        val fixture = RelationPagingFixture.live()
        val other = RelationPagingFixture.live()
        var attempts = 0
        val reads =
            ReacquiringQueryReferences(
                unavailable(fixture),
                ExactReferenceReacquisition { _, _ ->
                    attempts++
                    CanonicalSelectorDecoding.Decoded(other.selector)
                },
            )
        repeat(2) {
            assertEquals(
                CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED),
                reads.acquireExact(fixture.exact, fixture.authority),
            )
        }
        assertEquals(1, attempts)
        assertEquals(null, reads.readAcquisitions())
    }

    private fun unavailable(fixture: RelationPagingFixture): QueryReferenceAuthority =
        object : QueryReferenceAuthority by fixture.references {
            override fun restoreExact(
                token: ProtocolText,
                current: SemanticReadAuthority,
            ): CanonicalSelectorDecoding<SymbolSelector> =
                CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE)
        }

    @Test
    fun `fresh reads reacquire once but strict restoration cannot use that authority`() = runTest {
        val fixture = RelationPagingFixture.live()
        var attempts = 0
        val strict =
            object : QueryReferenceAuthority by fixture.references {
                override fun restoreExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> =
                    CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE)
            }
        val reads =
            ReacquiringQueryReferences(
                strict,
                ExactReferenceReacquisition { _, _ ->
                    attempts++
                    CanonicalSelectorDecoding.Decoded(fixture.selector)
                },
            )
        repeat(2) {
            assertEquals(
                CanonicalSelectorDecoding.Decoded(fixture.selector),
                reads.acquireExact(fixture.exact, fixture.authority),
            )
        }
        assertEquals(1, attempts)
        val encoded =
            Json.encodeToJsonElement(
                    io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions.serializer(),
                    reads.readAcquisitions()!!,
                )
                .jsonObject
                .getValue("references")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("REFERENCE_UNAVAILABLE", encoded["reason"]?.jsonPrimitive?.content)
        assertEquals(
            CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE),
            reads.restoreExact(fixture.exact, fixture.authority),
        )
    }

    @Test
    fun `stale authority acquisition reports its finite reason while current references need no acquisition`() =
        runTest {
            val fixture = RelationPagingFixture.live()
            var acquisitions = 0
            val stale =
                object : QueryReferenceAuthority by fixture.references {
                    override fun restoreExact(
                        token: ProtocolText,
                        current: SemanticReadAuthority,
                    ): CanonicalSelectorDecoding<SymbolSelector> =
                        CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
                }
            val reads =
                ReacquiringQueryReferences(
                    stale,
                    ExactReferenceReacquisition { _, _ ->
                        acquisitions++
                        CanonicalSelectorDecoding.Decoded(fixture.selector)
                    },
                )
            assertEquals(
                CanonicalSelectorDecoding.Decoded(fixture.selector),
                reads.acquireExact(fixture.exact, fixture.authority),
            )
            assertEquals(
                io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitionReason.STALE_SEMANTIC_AUTHORITY,
                reads.readAcquisitions()!!.references.single().reason,
            )
            assertEquals(fixture.exact, reads.readAcquisitions()!!.references.single().previous)
            assertEquals(fixture.exact, reads.readAcquisitions()!!.references.single().current)
            assertEquals(1, acquisitions)
            val current =
                ReacquiringQueryReferences(
                    fixture.references,
                    ExactReferenceReacquisition { _, _ ->
                        error("Current authority cannot trigger acquisition")
                    },
                )
            val restored = current.acquireExact(fixture.exact, fixture.authority) as CanonicalSelectorDecoding.Decoded
            assertEquals(
                CanonicalSelectorEncoding.Encoded(fixture.exact),
                CanonicalSelectorCodec.encodeExact(restored.value),
            )
            assertEquals(null, current.readAcquisitions())
        }

    @Test
    fun `malformed and foreign authority never trigger a search`() = runTest {
        val fixture = RelationPagingFixture.live()
        for (failure in
            listOf(
                CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE,
                CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE,
                CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY,
            )) {
            val strict =
                object : QueryReferenceAuthority by fixture.references {
                    override fun restoreExact(
                        token: ProtocolText,
                        current: SemanticReadAuthority,
                    ): CanonicalSelectorDecoding<SymbolSelector> = CanonicalSelectorDecoding.Rejected(failure)
                }
            val reads =
                ReacquiringQueryReferences(
                    strict,
                    ExactReferenceReacquisition { _, _ -> error("No search is authorized") },
                )
            assertEquals(
                CanonicalSelectorDecoding.Rejected(failure),
                reads.acquireExact(fixture.exact, fixture.authority),
            )
        }
    }
}
