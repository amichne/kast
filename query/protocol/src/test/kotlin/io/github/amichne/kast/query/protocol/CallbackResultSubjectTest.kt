package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCallbackResultProjectionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class CallbackResultSubjectTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `issued subject keeps the native fingerprint and exact public reference`() {
        val subject =
            CallbackResultSubject.issue(RelationEndpoint.subject(fixture.selector), fixture.references).refined()
        assertEquals(fixture.selector.fingerprint.value, subject.native.value)
        assertEquals(QueryReferenceDocument.ExactSymbol(fixture.exact), subject.reference)
        val result = observation(true).callbackObservations.single().resultProjection
        val projected =
            result.projectResultProjection(CallbackProjection(fixture.references, fixture.authority), subject)
                as QueryCallbackResultProjectionDocument.Admitted
        assertEquals(QueryReferenceDocument.ExactSymbol(fixture.exact), projected.subject)
    }

    @Test
    fun `another native subject rejects before a source handle request`() {
        val other = RelationEndpoint.resolve(fixture.authority, fixture.selector.scope, wrapperEvidence()).refined()
        val subject = CallbackResultSubject.issue(other, fixture.references).refined()
        val result = observation(true).callbackObservations.single().resultProjection
        assertNull(result.projectResultProjection(forbiddenProjection(), subject))
    }

    @Test
    fun `another read authority rejects before a source handle request`() {
        val subject =
            CallbackResultSubject.issue(RelationEndpoint.subject(fixture.selector), fixture.references).refined()
        val result = observation(true).callbackObservations.single().resultProjection
        assertNull(result.projectResultProjection(forbiddenProjection(RelationPagingFixture.live().authority), subject))
    }

    private fun forbiddenProjection(
        basis: io.github.amichne.kast.workspace.contract.SemanticReadAuthority = fixture.authority
    ): CallbackProjection {
        val authority =
            object : QueryReferenceAuthority by fixture.references {
                override fun issueRangeCandidate(
                    lease: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
                    file: SymbolDiscoveryFileIdentity,
                    rawStartInclusive: Int,
                    rawEndExclusive: Int,
                ): CandidateSelectorTokenIssuance = error("Rejected membership must not request a source handle")
            }
        return CallbackProjection(authority, basis)
    }
}
