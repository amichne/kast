package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.publicNameQuery
import io.github.amichne.kast.appserver.publicToolCase
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_NAMESPACE_DESCRIPTION
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolSchemaTest {
    @Test
    fun `rejection corpus runs through production schema and typed admission`() {
        val cases =
            requireNotNull(javaClass.getResourceAsStream("/public-tools/schema-cases.json")).bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonArray
            }
        assertEquals(60, cases.size)
        cases.forEach { case ->
            val row = case.jsonObject
            val identity =
                PublicToolIdentity.entries.single { it.toolName == row.getValue("tool").jsonPrimitive.content }
            val arguments = row.getValue("arguments")
            val schemaValid = row.getValue("schema_valid").jsonPrimitive.boolean
            val admitted = row["admission_valid"]?.jsonPrimitive?.boolean ?: schemaValid
            assertEquals(
                schemaValid,
                PublicToolContract.schema(identity).admit(arguments)
                    is io.github.amichne.kast.kernel.Validation.Validated,
                "schema: ${row.getValue("id")}",
            )
            assertEquals(
                admitted,
                PublicToolContract.admit(identity, arguments) is Refinement.Refined,
                "admission: ${row.getValue("id")}",
            )
        }
    }

    @Test
    fun `typed accepted action variants pass the production schema`() {
        val root = (ProtocolText.parse(".") as Refinement.Refined).value
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val continuation =
            (QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000000")
                    as Refinement.Refined)
                .value
        val emptyFields = (BoundedProtocolList.create(emptyList<PublicToolFields>()) as Refinement.Refined).value
        val cases =
            listOf(
                PublicToolIdentity.CHECK_DIAGNOSTICS to
                    Json.encodeToJsonElement(PublicToolCheckDiagnostics.serializer(), PublicToolCheckDiagnostics(root)),
                PublicToolIdentity.QUERY_SYMBOLS to publicNameQuery(),
                PublicToolIdentity.QUERY_SYMBOLS to
                    Json.encodeToJsonElement(
                        PublicToolQuerySymbols.serializer(),
                        PublicToolQuerySymbols(
                            PublicToolRunAction(PublicToolAllSource(), null, PublicToolSymbolsOutput(emptyFields))
                        ),
                    ),
                PublicToolIdentity.QUERY_SYMBOLS to
                    Json.encodeToJsonElement(
                        PublicToolQuerySymbols.serializer(),
                        PublicToolQuerySymbols(
                            PublicToolRunAction(PublicToolResultSource(result), null, null, PublicToolRetention.RETAIN)
                        ),
                    ),
                PublicToolIdentity.QUERY_SYMBOLS to
                    Json.encodeToJsonElement(
                        PublicToolQuerySymbols.serializer(),
                        PublicToolQuerySymbols(PublicToolResumeAction(continuation)),
                    ),
                PublicToolIdentity.QUERY_SYMBOLS to
                    Json.encodeToJsonElement(
                        PublicToolQuerySymbols.serializer(),
                        PublicToolQuerySymbols(PublicToolReadResultAction(result, null, null)),
                    ),
            )
        cases.forEach { (identity, encoded) ->
            assertTrue(
                PublicToolContract.schema(identity).admit(encoded) is io.github.amichne.kast.kernel.Validation.Validated
            )
            assertTrue(PublicToolContract.admit(identity, encoded) is Refinement.Refined)
        }
    }

    @Test
    fun `schema syntax does not manufacture exact reference authority`() {
        val token = "NON_ISSUED_SCHEMA_TEST_ONLY"
        val reference = (ProtocolText.parse(token) as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolReferenceSource(refs), null, null))
        val admitted =
            (PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                ) as Refinement.Refined)
                .value
        val source =
            ((admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run).from
                as QueryFromDocument.References
        assertEquals(token, source.values.values.single().token.value)
        // Authenticity is deliberately deferred to the existing exact-reference owner.
    }

    @Test
    fun `ordered transformations stay ordered and empty fields retain their distinct meaning`() {
        val source =
            PublicToolAllSource(
                (BoundedProtocolList.create(listOf(PublicToolDeclarationKinds.FUNCTION)) as Refinement.Refined).value
            )
        val steps =
            (BoundedProtocolList.create(
                    listOf<PublicToolStep>(
                        PublicToolWhere(
                            PublicToolVisibilityPredicate(
                                (BoundedProtocolList.create(listOf(PublicToolValues.PUBLIC)) as Refinement.Refined)
                                    .value
                            )
                        ),
                        PublicToolExpandRelation(PublicToolRelation.CALLERS),
                        PublicToolDistinctSymbols,
                    )
                ) as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(emptyList<PublicToolFields>()) as Refinement.Refined).value
        val input =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolRunAction(source, steps, PublicToolSymbolsOutput(fields))),
            )
        val admitted = (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined).value
        val request = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(
            listOf(QueryStepDocument.Where::class, QueryStepDocument.Related::class, QueryStepDocument.Distinct::class),
            request.steps.values.map { it::class },
        )
        assertTrue((request.output as QueryOutputDocument.Symbols).fields.values.isEmpty())
        val reparsed =
            PublicToolContract.admit(admitted.identity, PublicToolContract.encode(admitted)) as Refinement.Refined
        assertEquals(request, (reparsed.value.canonical as PublicToolCanonical.Query).request)
    }

    @Test
    fun `diagnostics defaults are independent of telemetry limits`() {
        val path = (ProtocolText.parse("src/Main.kt") as Refinement.Refined).value
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.CHECK_DIAGNOSTICS,
                Json {
                        encodeDefaults = true
                        explicitNulls = true
                    }
                    .encodeToJsonElement(
                        PublicToolCheckDiagnostics.serializer(),
                        PublicToolCheckDiagnostics(path),
                    ),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Diagnostics).request
        assertEquals("src/Main.kt", request.path.value)
        assertEquals(100, request.limit.value)
    }

    @Test
    fun `scope and name admission fail closed without normalization or widening`() {
        (0..8).forEach { index ->
            assertTrue(
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, publicToolCase("invalid-directory-$index"))
                    is Refinement.Rejected,
                index.toString(),
            )
        }
        (0..4).forEach { index ->
            assertTrue(
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, publicToolCase("invalid-name-$index"))
                    is Refinement.Rejected,
                index.toString(),
            )
        }
        val packageName = (ProtocolText.parse("com.example") as Refinement.Refined).value
        val sourceSet = (ProtocolText.parse("integrationTest") as Refinement.Refined).value
        val scoped =
            PublicToolPackageScope(
                packageName,
                false,
                (BoundedProtocolList.create(listOf(sourceSet)) as Refinement.Refined).value,
            )
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                publicNameQuery(
                    "createOrder",
                    listOf(PublicToolDeclarationKinds.FUNCTION),
                    scoped,
                    PublicToolNameMatch.FUZZY,
                ),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.FUZZY, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(QueryContainmentDocument.DIRECT, source.scope.packageName!!.containment)
        assertEquals(listOf("integrationTest"), source.scope.sourceSets.values.map { it.value })
        assertNull(source.scope.directory)
    }

    @Test
    fun `duplicate sets reject at the shared server boundary`() {
        listOf("duplicate-kinds", "duplicate-source-sets", "duplicate-visibility", "duplicate-output-symbol-fields")
            .forEach { id ->
                assertTrue(
                    PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, publicToolCase(id))
                        is Refinement.Rejected
                )
            }
    }

    @Test
    fun `strict projections share required closed objects and their target registration formats`() {
        val appServer = read("tools.app-server.json").jsonObject
        assertEquals(PUBLIC_TOOL_NAMESPACE_DESCRIPTION, appServer.getValue("description").jsonPrimitive.content)
        val registrations = appServer.getValue("tools").jsonArray
        val responses = read("tools.responses.json").jsonArray
        PublicToolIdentity.entries.forEach { identity ->
            val app =
                registrations
                    .single { it.jsonObject.getValue("name").jsonPrimitive.content == identity.toolName }
                    .jsonObject
            val response =
                responses
                    .single { it.jsonObject.getValue("name").jsonPrimitive.content == "kast_${identity.toolName}" }
                    .jsonObject
            assertFalse("strict" in app)
            assertEquals(JsonPrimitive(true), response["strict"])
            assertEquals(identity.description, app.getValue("description").jsonPrimitive.content)
            assertEquals(PublicToolContract.parameters(identity), app["inputSchema"])
            assertEquals(PublicToolContract.generationParameters(identity), response["parameters"])
            visit(response.getValue("parameters").jsonObject) { node ->
                assertTrue(
                    node.keys.intersect(setOf("\$id", "\$schema", "default", "discriminator", "uniqueItems")).isEmpty()
                )
                node["properties"]?.let {
                    assertEquals(JsonPrimitive(false), node["additionalProperties"])
                    assertEquals(
                        it.jsonObject.keys,
                        node.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    )
                }
            }
        }
    }

    @Test
    fun `tool prompts contain only reachable definitions`() {
        assertEquals(
            setOf("ExecutionBudget"),
            PublicToolContract.generationParameters(PublicToolIdentity.CHECK_DIAGNOSTICS)
                .getValue("\$defs")
                .jsonObject
                .keys,
        )
    }

    @Test
    fun `schema and tool identity are retained through encoding`() {
        val admitted =
            (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, publicNameQuery()) as Refinement.Refined).value
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.encodeToString(PublicToolRequestSerializer(PublicToolIdentity.CHECK_DIAGNOSTICS), admitted)
        }
        val raw =
            PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS).admit(PublicToolContract.encode(admitted))
                as io.github.amichne.kast.kernel.Validation.Validated
        assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaMismatch),
            PublicToolContract.admit(PublicToolIdentity.CHECK_DIAGNOSTICS, raw.value),
        )
    }

    private fun admit(identity: PublicToolIdentity, raw: String): AdmittedPublicTool =
        (PublicToolContract.admit(identity, Json.parseToJsonElement(raw)) as Refinement.Refined).value

    private fun read(name: String) =
        requireNotNull(PublicToolContract::class.java.getResourceAsStream(name)).bufferedReader().use {
            Json.parseToJsonElement(it.readText())
        }

    private fun visit(node: JsonObject, check: (JsonObject) -> Unit) {
        check(node)
        listOf("properties", "\$defs").forEach { node[it]?.jsonObject?.values?.forEach { visit(it.jsonObject, check) } }
        node["anyOf"]?.jsonArray?.forEach { visit(it.jsonObject, check) }
        node["items"]?.let { visit(it.jsonObject, check) }
    }
}
