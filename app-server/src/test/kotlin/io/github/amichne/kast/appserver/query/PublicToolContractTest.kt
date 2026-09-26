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
    fun `query symbols admits typed concatenation and structured filtering as ordered steps`() {
        val reference = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val value = (ProtocolText.parse("class") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val predicate =
            QueryPredicateDocument.Primitive(
                QueryPrimitiveFieldDocument.KIND,
                QueryPrimitiveOperatorDocument.EQUALS,
                value,
            )
        val steps =
            (BoundedProtocolList.create(
                    listOf<PublicToolStep>(
                        PublicToolConcat(PublicToolReferenceSource(refs)),
                        PublicToolWhere(predicate),
                        PublicToolDistinctSymbols,
                    )
                ) as Refinement.Refined)
                .value
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolReferenceSource(refs), steps, null))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val lowered =
            ((admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run).steps.values
        assertEquals(
            listOf(
                QueryStepDocument.Concat::class,
                QueryStepDocument.Where::class,
                QueryStepDocument.Distinct::class,
            ),
            lowered.map { it::class },
        )
        assertEquals(
            refs.values,
            ((lowered[0] as QueryStepDocument.Concat).input as QueryFromDocument.References).values.values.map {
                it.token
            },
        )
        assertEquals(predicate, (lowered[1] as QueryStepDocument.Where).predicate)
    }

    @Test
    fun `structured predicates preserve each legal primitive field and operator through lowering`() {
        val cases =
            listOf(
                Triple(QueryPrimitiveFieldDocument.NAME, QueryPrimitiveOperatorDocument.EQUALS, "PaymentService"),
                Triple(QueryPrimitiveFieldDocument.KIND, QueryPrimitiveOperatorDocument.NOT_EQUALS, "function"),
                Triple(QueryPrimitiveFieldDocument.FILE, QueryPrimitiveOperatorDocument.STARTS_WITH, "/workspace"),
                Triple(QueryPrimitiveFieldDocument.FILE, QueryPrimitiveOperatorDocument.ENDS_WITH, ".kt"),
                Triple(QueryPrimitiveFieldDocument.KIND, QueryPrimitiveOperatorDocument.STARTS_WITH, "func"),
            )
        cases.forEach { (field, operator, rawValue) ->
            val predicate =
                QueryPredicateDocument.Primitive(
                    field,
                    operator,
                    (ProtocolText.parse(rawValue) as Refinement.Refined).value,
                )
            val steps =
                (BoundedProtocolList.create(listOf<PublicToolStep>(PublicToolWhere(predicate))) as Refinement.Refined)
                    .value
            val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolAllSource(), steps, null))
            val admitted =
                PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
                ) as Refinement.Refined
            val lowered =
                ((admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run)
                    .steps
                    .values
                    .single()
            assertEquals(predicate, (lowered as QueryStepDocument.Where).predicate)
        }
    }

    @Test
    fun `query symbols source field lowers to canonical source projection`() {
        val refs =
            (BoundedProtocolList.create(listOf((ProtocolText.parse("NON_ISSUED") as Refinement.Refined).value))
                    as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.SOURCE)) as Refinement.Refined).value
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(
                        PublicToolRunAction(
                            PublicToolReferenceSource(refs),
                            PublicToolDefaults.steps,
                            QueryOutputDocument.Symbols(fields),
                        )
                    ),
                ),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(
            listOf(QuerySymbolFieldDocument.SOURCE),
            (request.output as QueryOutputDocument.Symbols).fields.values,
        )
    }

    @Test
    fun `typed pipeline and output continuations retain exact opaque bytes through facade lowering`() {
        val tokens =
            listOf(
                (QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000000")
                        as Refinement.Refined)
                    .value,
                (QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000000")
                        as Refinement.Refined)
                    .value,
            )
        tokens.forEach { token ->
            val document = PublicToolQuerySymbols(PublicToolResumeAction(token))
            val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), document)
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
            val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request
            assertEquals(token, (canonical as QueryRunRequest.Resume).continuation)
            assertEquals(
                token.value,
                PublicToolContract.encode(admitted.value)
                    .jsonObject
                    .getValue("request")
                    .jsonObject
                    .getValue("continuation")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `retained result source lowers without reconstructing exact symbol references`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(PublicToolResultSource(reference), null, null, PublicToolRetention.RETAIN)
            )
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryFromDocument.Result(reference), run.from)
        assertEquals(QueryRetentionModeDocument.RETAIN, run.retention)
        assertEquals(
            "result",
            PublicToolContract.encode(admitted.value)
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `selected retained rows lower through source concat and retained only set steps`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val rowId =
            (QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined)
                .value
        val rowIds = (BoundedProtocolList.create(listOf(rowId)) as Refinement.Refined).value
        val selected = PublicToolResultSource(reference, rowIds)
        val steps =
            (BoundedProtocolList.create(
                    listOf<PublicToolStep>(
                        PublicToolConcat(selected),
                        PublicToolIntersect(selected),
                        PublicToolUnion(selected),
                        PublicToolDifference(selected),
                    )
                ) as Refinement.Refined)
                .value
        val input = PublicToolQuerySymbols(PublicToolRunAction(selected, steps, null))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val expected = QueryFromDocument.Result(reference, rowIds)
        assertEquals(expected, request.from)
        assertEquals(expected, (request.steps.values[0] as QueryStepDocument.Concat).input)
        assertEquals(expected, (request.steps.values[1] as QueryStepDocument.Intersect).right)
        assertEquals(expected, (request.steps.values[2] as QueryStepDocument.Union).right)
        assertEquals(expected, (request.steps.values[3] as QueryStepDocument.Difference).right)

        val emptyRows = (BoundedProtocolList.create(emptyList<QueryResultRowReference>()) as Refinement.Refined).value
        val emptyInput =
            PublicToolQuerySymbols(PublicToolRunAction(PublicToolResultSource(reference, emptyRows), null, null))
        val emptyAdmitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), emptyInput),
            ) as Refinement.Refined
        val emptyRequest = (emptyAdmitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(emptyRows, (emptyRequest.from as QueryFromDocument.Result).rowIds)
    }

    @Test
    fun `read result carries a presentation cursor and projection without execution plan`() {
        val reference =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val cursor = (QueryResultCursor.parse(7) as Refinement.Refined).value
        val fields =
            (BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.SIGNATURE)) as Refinement.Refined).value
        val input =
            PublicToolQuerySymbols(PublicToolReadResultAction(reference, cursor, QueryOutputDocument.Symbols(fields)))
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input),
            ) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
        assertEquals(reference, request.result)
        assertEquals(cursor, request.cursor)
        assertEquals(
            listOf(QuerySymbolFieldDocument.SIGNATURE),
            request.symbolOutput.fields.values,
        )
        assertEquals(
            7,
            PublicToolContract.encode(admitted.value)
                .jsonObject
                .getValue("request")
                .jsonObject
                .getValue("cursor")
                .jsonPrimitive
                .int,
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
                    output =
                        QueryOutputDocument.Symbols(
                            (BoundedProtocolList.create(
                                    listOf(
                                        QuerySymbolFieldDocument.NAME,
                                        QuerySymbolFieldDocument.LOCATION,
                                        QuerySymbolFieldDocument.SIGNATURE,
                                    )
                                ) as Refinement.Refined)
                                .value
                        ),
                ),
            )
        val request =
            ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
                as QueryRunRequest.Run
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
        val emptyFields =
            (BoundedProtocolList.create(emptyList<QuerySymbolFieldDocument>()) as Refinement.Refined).value
        val cases =
            listOf(
                PublicToolIdentity.CHECK_DIAGNOSTICS to
                    Json.encodeToJsonElement(PublicToolCheckDiagnostics.serializer(), PublicToolCheckDiagnostics(root)),
                PublicToolIdentity.QUERY_SYMBOLS to publicNameQuery(),
                PublicToolIdentity.QUERY_SYMBOLS to
                    Json.encodeToJsonElement(
                        PublicToolQuerySymbols.serializer(),
                        PublicToolQuerySymbols(
                            PublicToolRunAction(PublicToolAllSource(), null, QueryOutputDocument.Symbols(emptyFields))
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
                            QueryPredicateDocument.Visibility(
                                (BoundedProtocolList.create(listOf(QueryVisibilityDocument.PUBLIC))
                                        as Refinement.Refined)
                                    .value
                            )
                        ),
                        PublicToolExpandRelation(PublicToolRelation.CALLERS),
                        PublicToolDistinctSymbols,
                    )
                ) as Refinement.Refined)
                .value
        val fields = (BoundedProtocolList.create(emptyList<QuerySymbolFieldDocument>()) as Refinement.Refined).value
        val input =
            Json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolRunAction(source, steps, QueryOutputDocument.Symbols(fields))),
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
