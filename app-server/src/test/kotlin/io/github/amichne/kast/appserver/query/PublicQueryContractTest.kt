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
    fun `declaring types own concrete collection and enum defaults`() {
        val search = PublicQuerySearch(
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("Order") as Refinement.Refined).value,
        )
        val document = PublicQueryDocument(PublicQueryDocumentType.QUERY, search)
        assertEquals(PublicQueryMatch.EXACT, search.match)
        val scope = search.scope as PublicQueryScope.Directory
        assertEquals(".", scope.value.value)
        assertEquals(PublicQueryContainment.RECURSIVE, scope.containment)
        assertEquals(listOf("main", "test"), scope.sourceSets.values.map { it.value })
        assertEquals(4, search.kinds.values.size)
        assertEquals(emptyList<PublicQueryStep>(), document.steps.values)
        assertEquals(listOf(PublicQueryField.NAME, PublicQueryField.LOCATION), document.select.values)
    }

    @Test
    fun `public enum inputs and encoding use caps case`() {
        val input = """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","match":"FUZZY","kinds":["TYPE_ALIAS"]},"steps":[{"type":"EXPAND","relation":"TYPE_USES"},{"type":"FILTER","visibility":["PUBLIC"]}],"select":["NAME","SIGNATURE"]}"""
        val request = json.decodeFromString(PublicQueryRequestSerializer, input)
        val encoded = json.encodeToString(PublicQueryRequestSerializer, request)
        assertTrue(encoded.contains("TYPE_USES"))
        assertTrue(encoded.contains("TYPE_ALIAS"))
        assertEquals(canonical(request.canonicalRequest), canonical(parse(encoded)))
    }

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
    fun `omitted controls match explicit declaring defaults`() {
        val short = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"OrderService"}}""")
        val explicit = parse("""{
            "type":"QUERY",
            "from":{"type":"SEARCH","query":"OrderService","match":"EXACT",
                "kinds":["CLASS","FUNCTION","PROPERTY","TYPE_ALIAS"],
                "scope":{"type":"DIRECTORY","value":".","containment":"RECURSIVE","sourceSets":["main","test"]}},
            "steps":[],"select":["NAME","LOCATION"]
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
        val request = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"submitOrder"},"steps":[{"type":"EXPAND","relation":"CALLERS"},{"type":"FILTER","visibility":["PUBLIC"]}]}""")
        assertTrue(request.steps.values[0] is QueryStepDocument.Related)
        assertTrue(request.steps.values[1] is QueryStepDocument.Where)
    }

    @Test
    fun `public encoding remains acceptable to the public CLI serializer`() {
        val admittedForRoundTrip = json.decodeFromString(PublicQueryRequestSerializer, """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","kinds":["CLASS"]},"steps":[{"type":"EXPAND","relation":"TYPE_USES"}],"select":[]}""")
        val serialized = json.encodeToString(PublicQueryRequestSerializer, admittedForRoundTrip)
        val decoded = parse(serialized)
        assertEquals(canonical(admittedForRoundTrip.canonicalRequest), canonical(decoded))
        assertTrue(serialized.contains("TYPE_USES"))
        assertTrue(!serialized.contains("type_uses"))
    }

    @Test
    fun `schema and typed parser reject named misuse without fallback`() {
        val rejected = listOf(
            """{"type":"QUERY","from":{"type":"SEARCH","query":" "}}""",
            """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","matching":"fuzzy"}}""",
            """{"type":"QUERY","from":{"type":"ALL","kinds":[]}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","sourceSets":[" "]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","sourceSets":["integrationTest","integrationTest"]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","sourceSets":[1]}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":"services","containment":"DESCENDANTS"}}}""",
            """{"type":"QUERY","from":{"type":"REFS","refs":["candidate:v2:not-exact"]}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"steps":[{"type":"INSPECT"}]}""",
            """{"type":"QUERY","from":{"type":"ALL"},"execution":{"kind":"exhaustive"}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"select":["NAME","NAME"]}""",
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
        val request = parse("""{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":"services/orders","sourceSets":["main"]}}}""")
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
        val fuzzy = parse("""{"type":"QUERY","from":{"type":"SEARCH","query":"Order","match":"FUZZY"}}""")
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME,
            ((exact.from as QueryFromDocument.Symbols).match as QueryMatchDocument.Name).matching)
        assertEquals(SymbolDiscoveryMatchDocument.FUZZY,
            ((fuzzy.from as QueryFromDocument.Symbols).match as QueryMatchDocument.Name).matching)
    }

    @Test
    fun `custom source-set names survive lowering and public round trip`() {
        val input = """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","sourceSets":["integrationTest","commonMain","jvmTest"]}}}"""
        val admitted = json.decodeFromString(PublicQueryRequestSerializer, input)
        val encoded = json.encodeToString(PublicQueryRequestSerializer, admitted)
        assertEquals(canonical(admitted.canonicalRequest), canonical(parse(encoded)))
        val source = admitted.canonicalRequest.from as QueryFromDocument.Symbols
        assertEquals(listOf("integrationTest", "commonMain", "jvmTest"), source.scope.sourceSets.values.map { it.value })
    }

    @Test
    fun `containment belongs to either exclusive scope target and round trips`() {
        listOf("DIRECTORY" to "services", "PACKAGE" to "com.example").forEach { (type, value) ->
            listOf("DIRECT" to QueryContainmentDocument.DIRECT, "RECURSIVE" to QueryContainmentDocument.DESCENDANTS).forEach { (containment, expected) ->
                val input = """{"type":"QUERY","from":{"type":"ALL","scope":{
                    "type":"$type","value":"$value","containment":"$containment"
                }}}"""
                val admitted = json.decodeFromString(PublicQueryRequestSerializer, input)
                val scope = (admitted.canonicalRequest.from as QueryFromDocument.Symbols).scope
                when (type) {
                    "DIRECTORY" -> {
                        assertEquals(expected, scope.directory!!.containment)
                        assertEquals(value, scope.directory!!.path.value)
                        org.junit.jupiter.api.Assertions.assertNull(scope.packageName)
                    }
                    "PACKAGE" -> {
                        assertEquals(expected, scope.packageName!!.containment)
                        assertEquals(value, scope.packageName!!.name.value)
                        org.junit.jupiter.api.Assertions.assertNull(scope.directory)
                    }
                }
                val encoded = json.encodeToString(PublicQueryRequestSerializer, admitted)
                assertTrue(encoded.contains(containment))
                assertEquals(canonical(admitted.canonicalRequest), canonical(parse(encoded)))
            }
        }
    }

    @Test
    fun `null defaults lowercase enums and competing scope fields are rejected`() {
        val inputs = listOf(
            """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","match":null}}""",
            """{"type":"QUERY","from":{"type":"ALL","kinds":null}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":null}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"steps":null}""",
            """{"type":"QUERY","from":{"type":"ALL"},"select":null}""",
            """{"type":"QUERY","from":{"type":"ALL","kinds":["class"]}}""",
            """{"type":"QUERY","from":{"type":"SEARCH","query":"Order","match":"exact"}}""",
            """{"type":"QUERY","from":{"type":"ALL"},"select":["name"]}""",
            """{"type":"QUERY","from":{"type":"ALL"},"steps":[{"type":"EXPAND","relation":"type-uses"}]}""",
            """{"type":"QUERY","from":{"type":"ALL"},"steps":[{"type":"FILTER","visibility":["public"]}]}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","containment":null}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","sourceSets":null}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","containment":"recursive"}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"SCOPE","directory":{"type":"DIRECTORY","path":"services"},"package":{"type":"PACKAGE","name":"com.example"}}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":"services","package":"com.example"}}}""",
            """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"PACKAGE"}}}""",
        )
        inputs.forEach { raw ->
            assertTrue(PublicQueryContract.admit(Json.parseToJsonElement(raw)) is Refinement.Rejected, raw)
            assertThrows(SerializationException::class.java) { parse(raw) }
        }
    }

    @Test
    fun `scope discriminator refines value into its own grammar`() {
        listOf("/absolute", "C:/absolute", "../parent", "a/../b", "a/./b", "a//b", "a/   /b", "a\\b").forEach { path ->
            val encodedPath = kotlinx.serialization.json.JsonPrimitive(path).toString()
            val raw = """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":$encodedPath}}}"""
            assertTrue(PublicQueryContract.admit(Json.parseToJsonElement(raw)) is Refinement.Rejected, path)
        }
        listOf("a..b", ".", "a/b", "a.").forEach { name ->
            val raw = """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"PACKAGE","value":"$name"}}}"""
            assertTrue(PublicQueryContract.admit(Json.parseToJsonElement(raw)) is Refinement.Rejected, name)
        }
        // A directory named a..b is valid; package grammar must not be applied to it.
        parse("""{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":"a..b"}}}""")
        val directRoot = parse("""{"type":"QUERY","from":{"type":"ALL","scope":{"type":"DIRECTORY","value":".","containment":"DIRECT"}}}""")
        assertEquals(".", (directRoot.from as QueryFromDocument.Symbols).scope.directory!!.path.value)
    }

    @Test
    fun `long package names remain bounded data through full admission`() {
        val prefix = "a.".repeat(10_000)
        val valid = "${prefix}b"
        val raw = """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"PACKAGE","value":"$valid"}}}"""
        val admitted = PublicQueryContract.admit(Json.parseToJsonElement(raw)) as Refinement.Refined
        val scope = (admitted.value.canonicalRequest.from as QueryFromDocument.Symbols).scope
        assertEquals(valid, scope.packageName!!.name.value)
        val invalid = """{"type":"QUERY","from":{"type":"ALL","scope":{"type":"PACKAGE","value":"${prefix}1"}}}"""
        assertTrue(PublicQueryContract.admit(Json.parseToJsonElement(invalid)) is Refinement.Rejected)
    }

    private fun parse(input: String): QueryRunRequest = json.decodeFromString(PublicQueryRequestSerializer, input).canonicalRequest
    private fun canonical(request: QueryRunRequest) = json.encodeToJsonElement(QueryRunRequest.serializer(), request)
}
