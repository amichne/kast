package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToolExpansionScopeContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `explicit relation source domain reaches canonical admission unchanged`() {
        val run = admit(ExpansionStepFixture.Related(sourceDomain()))
        assertEquals(expectedDomain(), (run.steps.values.single() as QueryStepDocument.Related).expansionScope)
    }

    @Test
    fun `explicit walk source domain reaches canonical admission unchanged`() {
        val run = admit(ExpansionStepFixture.Walk(sourceDomain()))
        assertEquals(expectedDomain(), (run.steps.values.single() as QueryStepDocument.Walk).expansionScope)
    }

    @Test
    fun `workspace and retained domain remain explicit alternatives on both steps`() {
        for ((provided, expected) in
            listOf(
                PublicToolImpactWorkspaceDomain to QueryExpansionScopeDocument.Workspace,
                PublicToolImpactRetainedDomain to QueryExpansionScopeDocument.RetainedSeed,
            )) {
            assertEquals(
                expected,
                (admit(ExpansionStepFixture.Related(provided)).steps.values.single() as QueryStepDocument.Related)
                    .expansionScope,
            )
            assertEquals(
                expected,
                (admit(ExpansionStepFixture.Walk(provided)).steps.values.single() as QueryStepDocument.Walk)
                    .expansionScope,
            )
        }
    }

    @Test
    fun `omitted and null expansion select defaults from walk extent`() {
        for (codec in
            listOf(
                json,
                Json {
                    encodeDefaults = true
                    explicitNulls = false
                },
            )) {
            assertEquals(
                QueryExpansionScopeDocument.Workspace,
                (admit(ExpansionStepFixture.Related(null), codec).steps.values.single() as QueryStepDocument.Related)
                    .expansionScope,
            )
            assertEquals(
                QueryExpansionScopeDocument.Workspace,
                (admit(ExpansionStepFixture.Walk(null), codec).steps.values.single() as QueryStepDocument.Walk)
                    .expansionScope,
            )
            assertEquals(
                QueryExpansionScopeDocument.RetainedSeed,
                (admit(ExpansionStepFixture.Walk(null, maximumDepth = 2), codec).steps.values.single()
                        as QueryStepDocument.Walk)
                    .expansionScope,
            )
        }
    }

    @Test
    fun `both advertised stages reference the existing closed domain owner`() {
        val definitions = PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS).getValue("\$defs").jsonObject
        for (stage in listOf("ExpandRelation", "Walk")) {
            val scope =
                definitions
                    .getValue(stage)
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("expansionScope")
                    .jsonObject
            assertEquals(
                "#/\$defs/ExpansionScope",
                scope.getValue("anyOf").jsonArray.first().jsonObject.getValue("\$ref").jsonPrimitive.content,
            )
        }
    }

    private fun admit(step: ExpansionStepFixture, codec: Json = json): QueryRunRequest.Run {
        val input = ExpansionInputFixture(ExpansionRunFixture(referenceSource(), listOf(step)))
        val raw = codec.encodeToJsonElement(ExpansionInputFixture.serializer(), input)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
        return (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
    }

    private fun referenceSource() = PublicToolReferenceSource(bounded(listOf(text("NON_ISSUED_SCOPE_TEST_ONLY"))))

    private fun sourceDomain() =
        PublicToolImpactSourceDomain(
            bounded(listOf(text("main"))),
            text("logging/src/main/kotlin"),
            false,
            PublicToolSourcePolicy.PRODUCTION_ONLY,
            PublicToolGeneratedSources.EXCLUDE,
        )

    private fun expectedDomain() =
        QueryExpansionScopeDocument.Sources(
            bounded(listOf(text("main"))),
            QueryDirectoryScopeDocument(text("logging/src/main/kotlin"), QueryContainmentDocument.DIRECT),
            QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected public scope admission: $failure")
        }
}

@Serializable private data class ExpansionInputFixture(val request: ExpansionRunFixture)

@Serializable
private data class ExpansionRunFixture(
    val source: PublicToolSource,
    val steps: List<ExpansionStepFixture>,
    val output: PublicToolOutput = PublicToolOccurrencesOutput,
    val type: ExpansionRunTag = ExpansionRunTag.RUN,
)

@Serializable
private enum class ExpansionRunTag {
    RUN
}

@Serializable
private sealed interface ExpansionStepFixture {
    @Serializable
    @SerialName("EXPAND_RELATION")
    data class Related(
        val expansionScope: PublicToolExpansionScope?,
        val relation: PublicToolRelation = PublicToolRelation.REFERENCES,
    ) : ExpansionStepFixture

    @Serializable
    @SerialName("WALK")
    data class Walk(
        val expansionScope: PublicToolExpansionScope?,
        val relation: PublicToolRelation = PublicToolRelation.REFERENCES,
        val maximumDepth: Int? = null,
    ) : ExpansionStepFixture
}
