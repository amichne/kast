package io.github.amichne.kast.api.contract.skill

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolQuerySchemaContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }
    private val requestJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    private val schemaRegistry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
    ) { builder ->
        builder.schemaIdResolvers { resolvers ->
            resolvers.mapPrefix(
                "https://kast.dev/contracts",
                "classpath:contracts",
            )
        }
    }

    @Test
    fun `canonical examples validate against the symbol query schemas`() {
        for (resource in listOf("request-minimal.json", "request-maximal.json")) {
            validateRequest(readContractResource("examples/$resource"))
        }
        for (resource in listOf(
            "response-success-exact.json",
            "response-success-lexical.json",
            "response-success-filters-facets.json",
            "response-failure.json",
        )) {
            validateResponse(readContractResource("examples/$resource"))
        }
    }

    @Test
    fun `Kotlin request model validates against the shared schema`() {
        val request = KastSymbolQueryRequest(
            workspaceRoot = "/workspace",
            query = "processor",
            modes = listOf(SymbolQueryMode.LEXICAL, SymbolQueryMode.STRUCTURAL),
            filters = KastSymbolQueryFilters(
                gradleProject = ":lib",
                relativePathPrefix = "lib/",
                productionOnly = true,
                excludePatterns = listOf("build-logic/**"),
                usageFacets = listOf(SymbolQueryUsageFacet.BRIDGE),
            ),
            includeEvidence = true,
            includeNextRequests = false,
        )
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "symbol/query")
            put(
                "params",
                requestJson.encodeToJsonElement(KastSymbolQueryRequest.serializer(), request),
            )
            put("id", 1)
        }

        validateRequest(envelope.toString())
    }

    @Test
    fun `Kotlin response models validate against the shared schema`() {
        val success = KastSymbolQuerySuccessResponse(
            query = "lib.Foo",
            availableSignals = AvailableSignals(
                exact = true,
                lexical = true,
                structural = true,
                graph = true,
                semantic = false,
            ),
            hardFilters = listOf(
                HardFilter(
                    field = "usageFacets",
                    value = JsonArray(listOf(JsonPrimitive("BRIDGE"))),
                    source = "declarations + symbol_references + file_metadata + declaration_supertypes",
                    satisfiedSymbolically = true,
                ),
            ),
            results = listOf(symbolQueryResult()),
        )
        validateResponse(jsonRpcResponse(success).toString())

        val failure = KastSymbolQueryFailureResponse(
            query = "",
            reason = SymbolQueryFailureReason.QUERY_TOO_BROAD,
            message = "query may be empty only when an anchor is provided",
        )
        validateResponse(jsonRpcResponse(failure).toString())
    }

    @Test
    fun `HardFilter value remains structured JSON`() {
        val hardFilter = HardFilter(
            field = "usageFacets",
            value = JsonArray(listOf(JsonPrimitive("BRIDGE"))),
            source = "declarations + symbol_references + file_metadata + declaration_supertypes",
            satisfiedSymbolically = true,
        )
        val encoded = json.encodeToString(hardFilter)

        assertTrue(encoded.contains(""""value":["BRIDGE"]"""))
    }

    private fun symbolQueryResult(): SymbolQueryResult = SymbolQueryResult(
        declaration = SymbolQueryDeclaration(
            fqId = 1,
            fqName = "lib.Foo",
            simpleName = "Foo",
            kind = "CLASS",
            visibility = "PUBLIC",
            usageFacets = listOf(SymbolQueryUsageFacet.PUBLIC_API, SymbolQueryUsageFacet.BRIDGE),
            modulePath = ":lib",
            sourceSet = "main",
            file = SymbolQueryDeclarationFile(
                prefixId = 1,
                dirPath = "lib",
                filename = "Foo.kt",
                path = "/workspace/lib/Foo.kt",
            ),
            declarationOffset = 12,
        ),
        rank = SymbolQueryRank(
            position = 1,
            sortScore = 1.2,
            components = SymbolQueryRankComponents(
                exact = 1.0,
                lexical = 0.0,
                structural = 1.0,
                graph = 0.0,
                semantic = null,
            ),
        ),
        signals = SymbolQuerySignals(
            exact = SymbolQueryExactSignal(
                matched = true,
                matches = listOf(
                    SymbolQueryExactMatch(
                        field = "fq_names.fq_name",
                        matchType = "EQUALS",
                        evidence = "lib.Foo",
                    ),
                ),
            ),
            lexical = SymbolQueryLexicalSignal(
                matched = false,
                matches = emptyList(),
            ),
            structural = SymbolQueryStructuralSignal(
                matched = true,
                constraints = listOf(
                    SymbolQueryStructuralConstraint(
                        field = "usageFacets",
                        operator = "ANY",
                        value = JsonArray(listOf(JsonPrimitive("BRIDGE"))),
                        source = "sqlite+derived",
                    ),
                ),
            ),
            graph = SymbolQueryGraphSignal(matched = false, paths = emptyList()),
            semantic = SymbolQuerySemanticSignal(
                available = false,
                matched = false,
                discoveryOnly = true,
                reason = "Semantic projection index is not configured",
            ),
        ),
        nextRequests = SymbolQueryNextRequests(
            symbolResolve = SymbolQueryNextRequest(
                method = "symbol/resolve",
                request = buildJsonObject {
                    put("symbol", "Foo")
                    put("fileHint", "Foo.kt")
                    put("kind", "class")
                    put("includeDeclarationScope", true)
                },
            ),
            symbolReferences = SymbolQueryNextRequest(
                method = "symbol/references",
                request = buildJsonObject {
                    put("symbol", "Foo")
                    put("fileHint", "Foo.kt")
                    put("kind", "class")
                    put("includeDeclaration", true)
                },
            ),
            symbolCallers = SymbolQueryNextRequest(
                method = "symbol/callers",
                request = buildJsonObject {
                    put("symbol", "Foo")
                    put("fileHint", "Foo.kt")
                    put("kind", "class")
                    put("direction", "incoming")
                    put("depth", 1)
                },
            ),
            rawResolve = SymbolQueryNextRequest(
                method = "raw/resolve",
                request = buildJsonObject {
                    put(
                        "position",
                        buildJsonObject {
                            put("filePath", "/workspace/lib/Foo.kt")
                            put("offset", 12)
                        },
                    )
                    put(
                        "symbol",
                        buildJsonObject {
                            put("symbol", "Foo")
                            put("fileHint", "Foo.kt")
                            put("kind", "class")
                        },
                    )
                },
            ),
        ),
    )

    private fun jsonRpcResponse(response: KastSymbolQueryResponse): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("result", json.encodeToJsonElement(KastSymbolQueryResponse.serializer(), response))
        put("id", 1)
    }

    private fun validateRequest(document: String) {
        validate("symbol-query-request.schema.json", document)
    }

    private fun validateResponse(document: String) {
        validate("symbol-query-response.schema.json", document)
    }

    private fun validate(schemaName: String, document: String) {
        val schema = schemaRegistry.getSchema(
            SchemaLocation.of("https://kast.dev/contracts/symbol-query/$schemaName"),
        )
        val errors = schema.validate(document, InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    private fun readContractResource(name: String): String {
        val resource = requireNotNull(
            javaClass.classLoader.getResource("contracts/symbol-query/$name"),
        ) { "Missing contract resource: $name" }
        return resource.readText()
    }
}
