package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.publicNameQuery
import io.github.amichne.kast.appserver.publicToolCase
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicToolContractTest {
    @Test
    fun `query symbols source field lowers to canonical source projection`() {
        val refs =
            (BoundedProtocolList.create(listOf((ProtocolText.parse("NON_ISSUED") as Refinement.Refined).value))
                    as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(listOf(PublicToolReturnFields.SOURCE)) as Refinement.Refined).value
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(PublicToolReferenceSource(refs), PublicToolDefaults.steps, fields),
                ),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request
        assertEquals(
            listOf(QuerySymbolFieldDocument.SOURCE),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
    }

    @Test
    fun `pipeline continuation retains exact opaque bytes through facade lowering`() {
        val token = (ProtocolText.parse("query:v1:EXAMPLE_NOT_ISSUED") as Refinement.Refined).value
        val refs =
            (BoundedProtocolList.create(
                    listOf((ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value)
                ) as Refinement.Refined)
                .value
        val document = PublicToolQuerySymbols(PublicToolReferenceSource(refs), null, null, token)
        val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), document)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request
        assertEquals(token, canonical.continuation)
        assertEquals(
            "query:v1:EXAMPLE_NOT_ISSUED",
            PublicToolContract.encode(admitted.value).jsonObject.getValue("continuation").jsonPrimitive.content,
        )
    }

    @Test
    fun `query name search compiles explicit null once to exact scoped exhaustive search`() {
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                publicNameQuery(
                    "OrderService",
                    listOf(PublicToolDeclarationKinds.CLASS),
                    fields =
                        listOf(
                            PublicToolReturnFields.NAME,
                            PublicToolReturnFields.LOCATION,
                            PublicToolReturnFields.SIGNATURE,
                        ),
                ),
            )
        val request = ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(listOf(QueryDeclarationKindDocument.CLASS), source.declarationKinds.values)
        assertEquals(listOf("main", "test"), source.scope.sourceSets.values.map { it.value })
        assertEquals(".", source.scope.directory!!.path.value)
        assertEquals(
            listOf(
                QuerySymbolFieldDocument.NAME,
                QuerySymbolFieldDocument.LOCATION,
                QuerySymbolFieldDocument.SIGNATURE,
            ),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
        assertTrue(request.steps.values.isEmpty())
    }
}

class PublicToolSchemaTest {
    @Test
    fun `the proposal corpus runs through production schema and typed admission`() {
        val cases =
            requireNotNull(javaClass.getResourceAsStream("/public-tools/schema-cases.json")).bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonArray
            }
        assertEquals(34, cases.size)
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
    fun `schema syntax does not manufacture exact reference authority`() {
        val token = "NON_ISSUED_SCHEMA_TEST_ONLY"
        val admitted =
            admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                """{"source":{"type":"symbol_refs","symbol_refs":["$token"]},"steps":null,"return_fields":null}""",
            )
        val source = (admitted.canonical as PublicToolCanonical.Query).request.from as QueryFromDocument.References
        assertEquals(token, (source.values.values.single() as QueryReferenceDocument.ExactSymbol).token.value)
        // Authenticity is deliberately deferred to the existing exact-reference owner.
    }

    @Test
    fun `ordered transformations stay ordered and empty fields retain their distinct meaning`() {
        val source = """{"type":"all_declarations","declaration_kinds":["function"],"scope":null}"""
        val input =
            """{"source":$source,"steps":[{"type":"filter_visibility","visibilities":["public"]},{"type":"expand_relation","relation":"callers"},{"type":"distinct_symbols"}],"return_fields":[]}"""
        val admitted = admit(PublicToolIdentity.QUERY_SYMBOLS, input)
        val request = (admitted.canonical as PublicToolCanonical.Query).request
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
        val request =
            (admit(
                        PublicToolIdentity.CHECK_DIAGNOSTICS,
                        """{"relative_path":"src/Main.kt","max_diagnostics":null}""",
                    )
                    .canonical as PublicToolCanonical.Diagnostics)
                .request
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
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.FUZZY, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(QueryContainmentDocument.DIRECT, source.scope.packageName!!.containment)
        assertEquals(listOf("integrationTest"), source.scope.sourceSets.values.map { it.value })
        assertNull(source.scope.directory)
    }

    @Test
    fun `duplicate sets reject at the shared server boundary`() {
        listOf("duplicate-kinds", "duplicate-source-sets", "duplicate-visibility", "duplicate-return-fields").forEach {
            id ->
            assertTrue(
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, publicToolCase(id)) is Refinement.Rejected
            )
        }
    }

    @Test
    fun `strict projections share required closed objects and their target registration formats`() {
        val registrations = read("tools.app-server.json").jsonObject.getValue("tools").jsonArray
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
            assertEquals(response["parameters"], app["inputSchema"])
            assertEquals(identity.description, app.getValue("description").jsonPrimitive.content)
            assertEquals(PublicToolContract.generationParameters(identity), app["inputSchema"])
            visit(app.getValue("inputSchema").jsonObject) { node ->
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
