package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `continuation cannot acquire a replacement subject handle`() = runTest {
        val fixture = RelationPagingFixture.live()
        val first = fixture.page() as io.github.amichne.kast.kernel.OperationOutcome.Qualified
        val continuation =
            (first.qualification as io.github.amichne.kast.protocol.contract.RelationReadQualification.Resumable)
                .continuation
        val reads =
            ReacquiringQueryReferences(
                unavailable(fixture),
                ExactReferenceReacquisition { _, _ -> error("A continuation cannot authorize a fresh read") },
            )
        val result =
            CanonicalRelationReadProtocol(fixture.operations, reads)
                .execute(
                    fixture.request(
                        io.github.amichne.kast.protocol.contract.RelationReadPositionDocument.Resume(continuation)
                    ),
                    fixture.authority,
                    fixture.budget,
                )
        assertTrue(result is io.github.amichne.kast.kernel.OperationOutcome.Rejected)
        assertEquals(listOf(0L, 1L, 2L), fixture.consumed)
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
    fun `relation operation returns accumulated facts and refreshed reference in one invocation`() = runTest {
        val fixture = RelationPagingFixture.live()
        val strict =
            object : QueryReferenceAuthority by fixture.references {
                override fun restoreExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> =
                    CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
            }
        val reads =
            ReacquiringQueryReferences(
                strict,
                ExactReferenceReacquisition { _, _ -> CanonicalSelectorDecoding.Decoded(fixture.selector) },
            )
        val result =
            CanonicalRelationReadProtocol(fixture.operations, reads)
                .execute(
                    fixture.request(io.github.amichne.kast.protocol.contract.RelationReadPositionDocument.Start),
                    fixture.authority,
                    fixture.budget,
                ) as io.github.amichne.kast.kernel.OperationOutcome.Qualified
        assertEquals(3, result.evidence.payload.relations.values.size)
        assertEquals(fixture.exact, result.evidence.payload.referenceAcquisitions!!.references.single().previous)
        val binding = io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings.relationRead
        val encoded = binding.encodeOutcome(result) as io.github.amichne.kast.protocol.wire.WireEncoding.Encoded
        val decoded =
            binding.decodeOutcome(encoded.document) as io.github.amichne.kast.protocol.wire.WireDecoding.Decoded
        assertEquals(result, decoded.value)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(encoded.document).toString()
        assertTrue(json.contains("reference_acquisitions"))
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
        assertEquals(
            CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE),
            reads.restoreExact(fixture.exact, fixture.authority),
        )
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
