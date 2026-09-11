package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicToolContractTest {
    @Test
    fun `class facade compiles explicit null once to exact scoped exhaustive search`() {
        val admitted = PublicToolContract.admit(PublicToolIdentity.SEARCH_CLASSES,
            Json.parseToJsonElement("""{"class_name":"OrderService","name_match":null,"scope":null}"""))
        val request = ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.EXACT_NAME, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(listOf(QueryDeclarationKindDocument.CLASS), source.declarationKinds.values)
        assertEquals(listOf("main", "test"), source.scope.sourceSets.values.map { it.value })
        assertEquals(".", source.scope.directory!!.path.value)
        assertEquals(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION, QuerySymbolFieldDocument.SIGNATURE),
            (request.output as QueryOutputDocument.Symbols).fields.values)
        assertTrue(request.steps.values.isEmpty())
    }
}

class PublicToolSchemaTest {
    @Test
    fun `the proposal corpus runs through production schema and typed admission`() {
        val cases = requireNotNull(javaClass.getResourceAsStream("/public-tools/schema-cases.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(32, cases.size)
        cases.forEach { case ->
            val row = case.jsonObject
            val identity = PublicToolIdentity.entries.single { it.toolName == row.getValue("tool").jsonPrimitive.content }
            val arguments = row.getValue("arguments")
            val valid = row.getValue("schema_valid").jsonPrimitive.boolean
            assertEquals(valid, PublicToolContract.schema(identity).admit(arguments) is io.github.amichne.kast.kernel.Validation.Validated,
                row.getValue("id").toString())
            assertEquals(valid, PublicToolContract.admit(identity, arguments) is Refinement.Refined,
                row.getValue("id").toString())
        }
    }

    @Test
    fun `schema syntax does not manufacture exact reference authority`() {
        val token = "NON_ISSUED_SCHEMA_TEST_ONLY"
        val admitted = admit(PublicToolIdentity.QUERY_SYMBOLS,
            """{"source":{"type":"symbol_refs","symbol_refs":["$token"]},"steps":null,"return_fields":null}""")
        val source = (admitted.canonical as PublicToolCanonical.Query).request.from as QueryFromDocument.References
        assertEquals(token, (source.values.values.single() as QueryReferenceDocument.ExactSymbol).token.value)
        // Authenticity is deliberately deferred to the existing exact-reference owner.
    }

    @Test
    fun `ordered transformations stay ordered and empty fields retain their distinct meaning`() {
        val source = """{"type":"all_declarations","declaration_kinds":["function"],"scope":null}"""
        val input = """{"source":$source,"steps":[{"type":"filter_visibility","visibilities":["public"]},{"type":"expand_relation","relation":"callers"},{"type":"distinct_symbols"}],"return_fields":[]}"""
        val admitted = admit(PublicToolIdentity.QUERY_SYMBOLS, input)
        val request = (admitted.canonical as PublicToolCanonical.Query).request
        assertEquals(listOf(QueryStepDocument.Where::class, QueryStepDocument.Related::class, QueryStepDocument.Distinct::class), request.steps.values.map { it::class })
        assertTrue((request.output as QueryOutputDocument.Symbols).fields.values.isEmpty())
        val reparsed = PublicToolContract.admit(admitted.identity, PublicToolContract.encode(admitted)) as Refinement.Refined
        assertEquals(request, (reparsed.value.canonical as PublicToolCanonical.Query).request)
    }

    @Test
    fun `diagnostics defaults are independent of telemetry limits`() {
        val request = (admit(PublicToolIdentity.CHECK_DIAGNOSTICS,
            """{"relative_path":"src/Main.kt","max_diagnostics":null}""").canonical as PublicToolCanonical.Diagnostics).request
        assertEquals("src/Main.kt", request.path.value)
        assertEquals(100, request.limit.value)
    }

    @Test
    fun `scope and name admission fail closed without normalization or widening`() {
        val directoryCases = listOf("/tmp", "../src", "a/../b", "a//b", "a/./b", "a/", "C:/src", "a\\\\b")
        directoryCases.forEach { path ->
            val input = """{"class_name":"Order","name_match":null,"scope":{"relative_directory_path":"$path","include_subdirectories":true,"source_set_names":null}}"""
            assertTrue(PublicToolContract.admit(PublicToolIdentity.SEARCH_CLASSES, Json.parseToJsonElement(input)) is Refinement.Rejected, path)
        }
        listOf("com.example.Order", "Order*", "Order()", "find orders", "").forEach { name ->
            assertTrue(PublicToolContract.admit(PublicToolIdentity.SEARCH_CLASSES,
                Json.parseToJsonElement("""{"class_name":"$name","name_match":null,"scope":null}""")) is Refinement.Rejected, name)
        }
        val input = """{"function_name":"createOrder","name_match":"fuzzy","scope":{"package_name":"com.example","include_subpackages":false,"source_set_names":["integrationTest"]}}"""
        val request = (admit(PublicToolIdentity.SEARCH_FUNCTIONS, input).canonical as PublicToolCanonical.Query).request
        val source = request.from as QueryFromDocument.Symbols
        assertEquals(SymbolDiscoveryMatchDocument.FUZZY, (source.match as QueryMatchDocument.Name).matching)
        assertEquals(QueryContainmentDocument.DIRECT, source.scope.packageName!!.containment)
        assertEquals(listOf("integrationTest"), source.scope.sourceSets.values.map { it.value })
        assertNull(source.scope.directory)
    }

    @Test
    fun `duplicate sets reject at the shared server boundary`() {
        listOf(
            PublicToolIdentity.SEARCH_DECLARATIONS to """{"declaration_name":"Order","name_match":null,"scope":null,"declaration_kinds":["class","class"]}""",
            PublicToolIdentity.SEARCH_CLASSES to """{"class_name":"Order","name_match":null,"scope":{"relative_directory_path":".","include_subdirectories":true,"source_set_names":["main","main"]}}""",
            PublicToolIdentity.QUERY_SYMBOLS to """{"source":{"type":"all_declarations","declaration_kinds":null,"scope":null},"steps":[{"type":"filter_visibility","visibilities":["public","public"]}],"return_fields":null}""",
            PublicToolIdentity.QUERY_SYMBOLS to """{"source":{"type":"all_declarations","declaration_kinds":null,"scope":null},"steps":null,"return_fields":["name","name"]}""",
        ).forEach { (identity, raw) -> assertTrue(PublicToolContract.admit(identity, Json.parseToJsonElement(raw)) is Refinement.Rejected) }
    }

    @Test
    fun `strict projections share required closed objects and their target registration formats`() {
        val registrations = read("tools.app-server.json").jsonObject.getValue("tools").jsonArray
        val responses = read("tools.responses.json").jsonArray
        PublicToolIdentity.entries.forEach { identity ->
            val app = registrations.single { it.jsonObject.getValue("name").jsonPrimitive.content == identity.toolName }.jsonObject
            val response = responses.single { it.jsonObject.getValue("name").jsonPrimitive.content == "kast_${identity.toolName}" }.jsonObject
            assertFalse("strict" in app)
            assertEquals(JsonPrimitive(true), response["strict"])
            assertEquals(response["parameters"], app["inputSchema"])
            assertEquals(identity.description, app.getValue("description").jsonPrimitive.content)
            assertEquals(PublicToolContract.generationParameters(identity), app["inputSchema"])
            visit(app.getValue("inputSchema").jsonObject) { node ->
                assertTrue(node.keys.intersect(setOf("\$id", "\$schema", "default", "discriminator", "uniqueItems")).isEmpty())
                node["properties"]?.let {
                    assertEquals(JsonPrimitive(false), node["additionalProperties"])
                    assertEquals(it.jsonObject.keys, node.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                }
            }
        }
    }

    @Test
    fun `ordinary tool prompts contain only reachable scope definitions`() {
        listOf(PublicToolIdentity.SEARCH_CLASSES, PublicToolIdentity.SEARCH_FUNCTIONS,
            PublicToolIdentity.SEARCH_DECLARATIONS).forEach { identity ->
            assertEquals(setOf("DirectoryScope", "PackageScope"),
                PublicToolContract.generationParameters(identity).getValue("\$defs").jsonObject.keys)
        }
        assertFalse("\$defs" in PublicToolContract.generationParameters(PublicToolIdentity.CHECK_DIAGNOSTICS))
    }

    @Test
    fun `schema and tool identity are retained through encoding`() {
        val admitted = admit(PublicToolIdentity.SEARCH_CLASSES, """{"class_name":"Order","name_match":null,"scope":null}""")
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.encodeToString(PublicToolRequestSerializer(PublicToolIdentity.SEARCH_FUNCTIONS), admitted)
        }
        val raw = PublicToolContract.schema(PublicToolIdentity.SEARCH_CLASSES).admit(PublicToolContract.encode(admitted)) as io.github.amichne.kast.kernel.Validation.Validated
        assertEquals(Refinement.Rejected(PublicToolInputFailure.SchemaMismatch), PublicToolContract.admit(PublicToolIdentity.SEARCH_FUNCTIONS, raw.value))
    }

    private fun admit(identity: PublicToolIdentity, raw: String): AdmittedPublicTool =
        (PublicToolContract.admit(identity, Json.parseToJsonElement(raw)) as Refinement.Refined).value
    private fun read(name: String) = requireNotNull(PublicToolContract::class.java.getResourceAsStream(name))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    private fun visit(node: JsonObject, check: (JsonObject) -> Unit) {
        check(node)
        listOf("properties", "\$defs").forEach { node[it]?.jsonObject?.values?.forEach { visit(it.jsonObject, check) } }
        node["anyOf"]?.jsonArray?.forEach { visit(it.jsonObject, check) }
        node["items"]?.let { visit(it.jsonObject, check) }
    }
}
