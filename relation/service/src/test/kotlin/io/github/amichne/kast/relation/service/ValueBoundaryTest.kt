package io.github.amichne.kast.relation.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryCompatibilityAssumption
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryRequiredEvidence
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValueBoundaryTest {
    @Test
    fun `reviewed retention requires a persistence boundary at domain admission`() {
        val fixture = RepresentationFixture()
        val source = fixture.boundary(BoundaryKind.SERIALIZATION)
        assertEquals(
            Refinement.Rejected(BoundaryModelFailure.KIND_MISMATCH),
            BoundaryModel.Terminal.admit(
                fixture.reference,
                source.reference,
                source,
                BoundaryTerminalMeaning.REVIEWED_RETENTION,
            ),
        )
    }

    @Test
    fun `cross repository continuation retains independent source and target bases`() {
        val producer = RepresentationFixture("/server", 7)
        val consumer = RepresentationFixture("/client", 19)
        val source = producer.boundary(BoundaryKind.SERIALIZATION)
        val target = consumer.boundary(BoundaryKind.SERIALIZATION)
        val model =
            BoundaryModel.Continuation.admit(
                    producer.reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    setOf(BoundaryCompatibilityAssumption.CONTRACT_COMPATIBLE),
                )
                .refined()
        val result = BoundaryArrival.connect(source, model).refined()
        assertEquals(source.site.basis, result.source.site.basis)
        assertEquals(target.site.basis, result.target.site.basis)
        org.junit.jupiter.api.Assertions.assertNotEquals(result.source.site.basis, result.target.site.basis)
    }

    @Test
    fun `stale writer basis is rejected without weakening the declared binding`() {
        val before = RepresentationFixture(generation = 1)
        val after = RepresentationFixture(generation = 2)
        val source = before.boundary(BoundaryKind.PERSISTENCE)
        val currentSource = after.boundary(BoundaryKind.PERSISTENCE)
        val target = after.boundary(BoundaryKind.PERSISTENCE, "reader")
        assertEquals(
            Refinement.Rejected(BoundaryModelFailure.BASIS_MISMATCH),
            BoundaryModel.Continuation.admit(
                before.reference,
                source.reference,
                target.reference,
                currentSource,
                target,
                emptySet(),
            ),
        )
    }

    @Test
    fun `reviewed terminal is distinct from unresolved terminal and retains persisted obligations`() {
        val fixture = RepresentationFixture()
        val source = fixture.boundary(BoundaryKind.PERSISTENCE)
        val model =
            BoundaryModel.Terminal.admit(
                    fixture.reference,
                    source.reference,
                    source,
                    BoundaryTerminalMeaning.REVIEWED_RETENTION,
                )
                .refined()
        val terminal = BoundaryArrival.terminal(source, model).refined()
        assertEquals(BoundaryTerminalMeaning.REVIEWED_RETENTION, terminal.model.meaning)
        assertEquals(3, terminal.obligations.single().required.size)
        assertEquals(
            BoundaryUnresolvedReason.MISSING_MODEL,
            BoundaryArrival.unresolved(source, BoundaryUnresolvedReason.MISSING_MODEL).reason,
        )
    }

    @Test
    fun `missing serialization model retains exact evidence needed to continue`() {
        val source = RepresentationFixture().boundary(BoundaryKind.SERIALIZATION)
        val result = BoundaryArrival.unresolved(source, BoundaryUnresolvedReason.MISSING_MODEL)
        assertEquals(source.reference, result.obligation.position.reference)
        assertEquals(
            setOf(BoundaryRequiredEvidence.REVIEWED_MODEL, BoundaryRequiredEvidence.EXACT_DOWNSTREAM_POSITION),
            result.obligation.required,
        )
    }

    @Test
    fun `same slot name under different contracts cannot admit a connection`() {
        val fixture = RepresentationFixture()
        val source = fixture.boundary(BoundaryKind.SERIALIZATION)
        val unrelated =
            BoundaryPosition.at(
                source.site,
                source.kind,
                BoundaryContractIdentity(fixture.id("unrelated-schema"), fixture.version),
                source.slot,
            )
        val target = fixture.boundary(BoundaryKind.SERIALIZATION, "response-reader")
        assertEquals(
            Refinement.Rejected(BoundaryModelFailure.SOURCE_MISMATCH),
            BoundaryModel.Continuation.admit(
                fixture.reference,
                source.reference,
                target.reference,
                unrelated,
                target,
                emptySet(),
            ),
        )
    }

    @Test
    fun `persisted connection carries decoding retention and migration obligations`() {
        val fixture = RepresentationFixture()
        val source = fixture.boundary(BoundaryKind.PERSISTENCE)
        val target = fixture.boundary(BoundaryKind.PERSISTENCE, "reader")
        val model =
            BoundaryModel.Continuation.admit(
                    fixture.reference,
                    source.reference,
                    target.reference,
                    source,
                    target,
                    emptySet(),
                )
                .refined()
        val connected = BoundaryArrival.connect(source, model).refined()
        assertEquals(
            setOf(
                BoundaryRequiredEvidence.RETENTION_POLICY,
                BoundaryRequiredEvidence.DECODING_COMPATIBILITY,
                BoundaryRequiredEvidence.MIGRATION_PROOF,
            ),
            connected.obligations.single().required,
        )
    }

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
