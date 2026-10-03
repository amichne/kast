package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryExpansionScopeAdmissionTest {
    @Test
    fun `source expansion refines ownership restrictions separately from presentation`() {
        val raw = scope("main")
        val expansion = (raw.boundary() as Refinement.Refined).value as RelationSearchBoundary.Explicit
        assertEquals(SymbolSourceKindPolicy.PRODUCTION_ONLY, expansion.scope.sourceKinds)
        assertEquals(listOf("main"), (expansion.sourceSets as SymbolDiscoverySourceSets.Exact).values.map { it.value })
        assertEquals("shared", expansion.directory?.directory?.value)
        val step = QueryStepDocument.Walk(RelationKindDocument.CALLEES, count(3), expansionScope = raw)
        assertEquals(expansion, (step.syntax() as io.github.amichne.kast.query.contract.QueryStepSyntax.Walk).expansion)
    }

    @Test
    fun `expansion scope encoded shape requires its discriminator and closed source fields`() {
        val encoded = Json.encodeToString(QueryExpansionScopeDocument.serializer(), scope("main"))
        assertEquals(
            Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/query/expansion-source-domain.json")).readText()
            ),
            Json.parseToJsonElement(encoded),
        )
        for (invalid in
            listOf(
                encoded.replace("SOURCE_DOMAIN", "UNKNOWN"),
                encoded.replace("\"type\":\"SOURCE_DOMAIN\",", ""),
                encoded.replace("\"source_policy\":\"production-only\",", ""),
                encoded.replace("\"source_sets\"", "\"package_name\""),
            )) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(QueryExpansionScopeDocument.serializer(), invalid)
            }
        }
    }

    @Test
    fun `duplicate source sets and escaping directories fail admission`() {
        val duplicate = assertInstanceOf(Refinement.Rejected::class.java, scope("main", "main").boundary())
        assertEquals(QueryExpansionScopeFailure.DUPLICATE_SOURCE_SET, duplicate.failure)
        val escaping =
            scope("main")
                .copy(directory = QueryDirectoryScopeDocument(text("../outside"), QueryContainmentDocument.DESCENDANTS))
        val rejected = assertInstanceOf(Refinement.Rejected::class.java, escaping.boundary())
        assertEquals(QueryExpansionScopeFailure.DIRECTORY_REJECTED, rejected.failure)
    }

    private fun scope(vararg sets: String) =
        QueryExpansionScopeDocument.Sources(
            BoundedProtocolList.create(sets.map(::text)).refined(),
            QueryDirectoryScopeDocument(text("shared"), QueryContainmentDocument.DESCENDANTS),
            QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun count(raw: Int) = ProtocolCount.parse(raw).refined()

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
