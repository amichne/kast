package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import kotlinx.coroutines.test.runTest
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
