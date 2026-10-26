package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCastKind
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryValueConditionsStorageTest {
    @Test
    fun `site and identity visitors both retain conditional facts and their type payload`() {
        val fixture = QueryImpactLedgerFixture()
        val receiver =
            ValueSite.fromCompiler(
                    fixture.owner,
                    fixture.owner.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .value()
        val short = qualified(receiver, "fixture.Selected")
        val long = qualified(receiver, "fixture." + "X".repeat(1000))
        assertTrue(QueryImpactRetainedGraph().site(short) > QueryImpactRetainedGraph().site(receiver))
        assertTrue(QueryImpactRetainedGraph().site(long) > QueryImpactRetainedGraph().site(short) + 2000)
        assertTrue(
            short.identity.storageBytes(QueryImpactRetainedGraph()) >
                receiver.identity.storageBytes(QueryImpactRetainedGraph())
        )
        assertTrue(
            long.identity.storageBytes(QueryImpactRetainedGraph()) >
                short.identity.storageBytes(QueryImpactRetainedGraph()) + 2000
        )
    }

    @Test
    fun `shared conditions are charged once but both site and identity retain references`() {
        val fixture = QueryImpactLedgerFixture()
        val receiver =
            ValueSite.fromCompiler(
                    fixture.owner,
                    fixture.owner.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .value()
        val site = qualified(receiver, "fixture.Selected")
        val graph = QueryImpactRetainedGraph()
        val first = site.identity.storageBytes(graph)
        val second = site.identity.storageBytes(graph)
        assertTrue(first > second)
        assertEquals(8L, second)
        assertTrue(graph.site(site) < QueryImpactRetainedGraph().site(site))
        assertEquals(8L, site.conditions.storageBytes(graph))
    }

    @Test
    fun `cast condition payload is charged for site and identity and shared references remain shared`() {
        val fixture = QueryImpactLedgerFixture()
        val source = fixture.producer
        for (kind in ValueCastKind.entries) {
            val short = castQualified(source, kind, "kotlin.Any", "kotlin.String")
            val longTarget = castQualified(source, kind, "kotlin.Any", "fixture." + "X".repeat(1000))
            val longOperand = castQualified(source, kind, "fixture." + "Y".repeat(1000), "kotlin.String")
            assertTrue(QueryImpactRetainedGraph().site(short) > QueryImpactRetainedGraph().site(source))
            assertTrue(
                short.identity.storageBytes(QueryImpactRetainedGraph()) >
                    source.identity.storageBytes(QueryImpactRetainedGraph())
            )
            for (long in listOf(longTarget, longOperand)) {
                assertTrue(QueryImpactRetainedGraph().site(long) > QueryImpactRetainedGraph().site(short) + 2000)
                assertTrue(
                    long.identity.storageBytes(QueryImpactRetainedGraph()) >
                        short.identity.storageBytes(QueryImpactRetainedGraph()) + 2000
                )
            }
            val graph = QueryImpactRetainedGraph()
            short.identity.storageBytes(graph)
            assertEquals(8L, short.identity.storageBytes(graph))
            assertTrue(graph.site(short) < QueryImpactRetainedGraph().site(short))
            assertEquals(8L, short.conditions.storageBytes(graph))
        }
    }

    @Test
    fun `cast origins remain authority bearing after conditions are transported to another site`() {
        val fixture = QueryImpactLedgerFixture()
        val qualified = castQualified(fixture.producer, ValueCastKind.SAFE, "kotlin.Any", "kotlin.String")
        val arrival = fixture.first.withState(conditions = qualified.conditions).value()
        assertFalse(arrival.hasForeignBasis(fixture.lease))
        assertTrue(arrival.hasForeignBasis(QueryImpactLedgerFixture(generation = 2).lease))
        assertEquals(fixture.producer.range, arrival.conditions.facts.single().origin.range)
    }

    private fun castQualified(source: ValueSite, kind: ValueCastKind, original: String, target: String): ValueSite {
        val fact =
            ValueCondition.CastSuccess.fromCompiler(
                    source,
                    source.range,
                    kind,
                    CanonicalCompilerType.fromCompiler(original).value(),
                    CanonicalCompilerType.fromCompiler(target).value(),
                )
                .value()
        return source.withState(conditions = source.conditions.requiring(fact).value()).value()
    }

    private fun qualified(source: ValueSite, type: String): ValueSite {
        val fact =
            ValueCondition.StableReceiver.fromCompiler(
                    source = source,
                    useRange = source.range,
                    kind = ValueReceiverKind.EXTENSION,
                    originalType = CanonicalCompilerType.fromCompiler("fixture.Choice").value(),
                    refinedType = CanonicalCompilerType.fromCompiler(type).value(),
                )
                .value()
        return source.withState(conditions = ValueConditions.Unconditional.requiring(fact).value()).value()
    }

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
