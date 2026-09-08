package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicQueryContractTest {
    private val json = Json

    @Test
    fun `minimal search has conservative deterministic defaults`() {
        val request = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"OrderService"}}""")
        val source = request.from as QueryFromDocument.Symbols
        val match = source.match as QueryMatchDocument.Name
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME, match.matching)
        assertEquals(listOf("main", "test"), source.scope.sourceSets.values.map { it.value })
        assertEquals(4, source.declarationKinds.values.size)
        assertEquals(emptyList<QueryStepDocument>(), request.steps.values)
        assertEquals(
            listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
    }

    @Test
    fun `omitted and null controls normalize identically`() {
        val short = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"OrderService"}}""")
        val explicit = parse("""{
            "type":"QUERY",
            "from":{"type":"SEARCH","query":"OrderService","match":null,"kinds":null,"scope":null},
            "steps":null,"select":null
        }""")
        assertEquals(canonical(short), canonical(explicit))
    }

    @Test
    fun `empty projection is not replaced by default projection`() {
        val request = parse("""{"type":"QUERY","from":{"type":"ALL"},"select":[]}""")
        assertTrue((request.output as QueryOutputDocument.Symbols).fields.values.isEmpty())
    }

    @Test
    fun `pipeline order survives lowering`() {
        val request = parse("""{
            "type":"QUERY","from":{"type":"SEARCH","query":"submitOrder"},
            "steps":[{"type":"EXPAND","relation":"callers"},{"type":"FILTER","visibility":["public"]}]
        }""")
        assertTrue(request.steps.values[0] is QueryStepDocument.Related)
        assertTrue(request.steps.values[1] is QueryStepDocument.Where)
    }

    @Test
    fun `public encoding remains acceptable to the public CLI serializer`() {
        val admittedForRoundTrip = json.decodeFromString(PublicQueryRequestSerializer, """{
            "type":"QUERY","from":{"type":"SEARCH","query":"Order","kinds":["class"]},
            "steps":[{"type":"EXPAND","relation":"type-uses"}],"select":[]
        }""")
        val serialized = json.encodeToString(PublicQueryRequestSerializer, admittedForRoundTrip)
        val decoded = parse(serialized)
        assertEquals(canonical(admittedForRoundTrip.canonicalRequest), canonical(decoded))
        assertTrue(serialized.contains("type-uses"))
        assertTrue(!serialized.contains("type_uses"))
    }

    @Test
    fun `schema and typed parser reject named misuse without fallback`() {
        val rejected = listOf(
            """{"type":"QUERY","from":{"type":"SEARCH","query":" "}}""",
            """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","matching":"fuzzy"}}""",
            """{"type":"QUERY","from":{"type":"ALL","kinds":[]}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"SCOPE","sourceSets":[" "]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"SCOPE","sourceSets":["integrationTest","integrationTest"]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"SCOPE","sourceSets":[1]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"SCOPE","directory":{"type":"DIRECTORY","path":"services","containment":"descendants"}}}}""",
            """{"type":"QUERY","from":{"type":"REFS","refs":["candidate:v2:not-exact"]}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"steps":[{"type":"INSPECT"}]}""",
            """{"type":"QUERY","from":{"type":"ALL"},"execution":{"kind":"exhaustive"}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"select":["name","name"]}""",
        )
        rejected.forEach { input ->
            assertTrue(PublicQueryContract.admit(json.parseToJsonElement(input)) is Refinement.Rejected)
            assertThrows(SerializationException::class.java) { parse(input) }
        }
    }

    @Test
    fun `reference transport never rewrites the issued token`() {
        // Syntactically shaped fixture only, not a live reference or compiler proof.
        val token = "exact:v2:EXAMPLE_NOT_ISSUED"
        val request = parse("""{"type":"QUERY","from":{"type":"REFS","refs":["$token"]}}""")
        val reference = (request.from as QueryFromDocument.References).values.values.single()
        assertEquals(token, (reference as QueryReferenceDocument.ExactSymbol).token.value)
        val admitted = json.decodeFromString(PublicQueryRequestSerializer,
            """{"type":"QUERY","from":{"type":"REFS","refs":["$token"]}}""")
        val roundTrip = parse(json.encodeToString(PublicQueryRequestSerializer, admitted))
        assertEquals(canonical(request), canonical(roundTrip))
    }

    @Test
    fun `directory containment defaults without dropping source-set restrictions`() {
        val request = parse("""{
            "type":"QUERY","from":{"type":"ALL","scope":{
                "type":"SCOPE","sourceSets":["main"],
                "directory":{"type":"DIRECTORY","path":"services/orders"}
            }}
        }""")
        val scope = (request.from as QueryFromDocument.Symbols).scope
        assertEquals(listOf("main"), scope.sourceSets.values.map { it.value })
        assertEquals(QueryContainmentDocument.DESCENDANTS, scope.directory!!.containment)
        assertEquals("services/orders", scope.directory!!.path.value)
    }

    @Test
    fun `schema proof from another contract cannot enter query lowering`() {
        val other = io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler.compile(
            Json.parseToJsonElement("""{"type":"object"}""") as kotlinx.serialization.json.JsonObject,
        ) as Refinement.Refined
        val value = other.value.admit(Json.parseToJsonElement(
            """{"type":"QUERY","from":{"type":"ALL"}}""",
        )) as io.github.amichne.kast.kernel.Validation.Validated
        assertEquals(
            Refinement.Rejected(PublicQueryInputFailure.SCHEMA_MISMATCH),
            PublicQueryContract.admit(value.value),
        )
    }

    @Test
    fun `fuzzy matching is explicit and never a fallback`() {
        val exact = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"Order"}}""")
        val fuzzy = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"Order","match":"fuzzy"}}""")
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME,
            ((exact.from as QueryFromDocument.Symbols).match as QueryMatchDocument.Name).matching)
        assertEquals(SymbolDiscoveryMatchDocument.FUZZY,
            ((fuzzy.from as QueryFromDocument.Symbols).match as QueryMatchDocument.Name).matching)
    }

    @Test
    fun `custom source-set names survive lowering and public round trip`() {
        val input = """{"type":"QUERY","from":{"type":"ALL","scope":{
            "type":"SCOPE","sourceSets":["integrationTest","commonMain","jvmTest"]
        }}}"""
        val admitted = json.decodeFromString(PublicQueryRequestSerializer, input)
        val encoded = json.encodeToString(PublicQueryRequestSerializer, admitted)
        assertEquals(canonical(admitted.canonicalRequest), canonical(parse(encoded)))
        val source = admitted.canonicalRequest.from as QueryFromDocument.Symbols
        assertEquals(listOf("integrationTest", "commonMain", "jvmTest"), source.scope.sourceSets.values.map { it.value })
    }

    @Test
    fun `recursive containment lowers for directory and package and round trips`() {
        val input = """{"type":"QUERY","from":{"type":"ALL","scope":{
            "type":"SCOPE",
            "directory":{"type":"DIRECTORY","path":"services","containment":"recursive"},
            "package":{"type":"PACKAGE","name":"com.example","containment":"recursive"}
        }}}"""
        val admitted = json.decodeFromString(PublicQueryRequestSerializer, input)
        val scope = (admitted.canonicalRequest.from as QueryFromDocument.Symbols).scope
        assertEquals(QueryContainmentDocument.DESCENDANTS, scope.directory!!.containment)
        assertEquals(QueryContainmentDocument.DESCENDANTS, scope.packageName!!.containment)
        val encoded = json.encodeToString(PublicQueryRequestSerializer, admitted)
        assertTrue(encoded.contains("recursive"))
        assertEquals(canonical(admitted.canonicalRequest), canonical(parse(encoded)))
    }

    private fun parse(input: String): QueryRunRequest = json.decodeFromString(PublicQueryRequestSerializer, input).canonicalRequest
    private fun canonical(request: QueryRunRequest) = json.encodeToJsonElement(QueryRunRequest.serializer(), request)
}
