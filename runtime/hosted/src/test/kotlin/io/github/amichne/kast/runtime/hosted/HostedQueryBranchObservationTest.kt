package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryBranch
import io.github.amichne.kast.query.contract.QueryBranchAccounting
import io.github.amichne.kast.query.contract.QueryBranchOutcome
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceObservation
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HostedQueryBranchObservationTest {
    @Test
    fun `encoded branch receipt joins the read without logging source and keeps unobserved work distinct from zero`() {
        val source = RelationEndpoint.subject(RelationPagingFixture.live().selector)
        val readId = UUID.fromString("00000000-0000-0000-0000-000000000123")
        for (work in listOf(QueryWorkUsage.Unobserved, QueryWorkUsage.Observed(QueryWorkCount.parse(0).refined()))) {
            val emitted = mutableListOf<HostedQueryBranchDocument>()
            val observer =
                hostedQueryBranchObservation(
                    HostedReadTraceObservation.Observed(HostedReadTraceIdentity.fromBoundary(readId)),
                    emitted::add,
                )
            observer.observe(
                QueryBranchAccounting(
                    QueryBranch.REFERENCES,
                    source,
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                    QueryWorkCount.parse(7).refined(),
                    work,
                    13.milliseconds,
                    QueryCount.parse(2).refined(),
                    QueryBranchOutcome.QUALIFIED,
                    setOf(QueryLimitation.RELATION_INCOMPLETE),
                )
            )
            val json = Json.encodeToJsonElement(HostedQueryBranchDocument.serializer(), emitted.single()).jsonObject
            assertEquals(expectedFields, json.keys)
            assertEquals(readId.toString(), json.getValue("readId").jsonPrimitive.content)
            assertEquals("REFERENCES", json.getValue("branch").jsonPrimitive.content)
            assertEquals("QUALIFIED", json.getValue("outcome").jsonPrimitive.content)
            assertEquals(7L, json.getValue("chargedWorkUnits").jsonPrimitive.long)
            assertEquals(13L, json.getValue("elapsedMillis").jsonPrimitive.long)
            assertEquals(2L, json.getValue("emittedFacts").jsonPrimitive.long)
            assertEquals(1L, json.getValue("reads").jsonPrimitive.long)
            if (work == QueryWorkUsage.Unobserved) assertEquals(JsonNull, json.getValue("examinedWorkUnits"))
            else assertEquals(0L, json.getValue("examinedWorkUnits").jsonPrimitive.long)
            assertFalse(json.toString().contains(source.file.stableValue))
            assertEquals(source.fingerprint.value, json.getValue("source").jsonPrimitive.content)
        }
    }

    private val expectedFields =
        setOf(
            "readId",
            "branch",
            "source",
            "authority",
            "effectiveExpansionDomain",
            "scope",
            "sourceKinds",
            "generatedSources",
            "reads",
            "chargedWorkUnits",
            "examinedWorkUnits",
            "elapsedMillis",
            "emittedFacts",
            "outcome",
            "limitations",
        )
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid branch observation fixture: $failure")
    }
