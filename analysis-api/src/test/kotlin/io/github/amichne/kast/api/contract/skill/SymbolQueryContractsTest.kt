package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolQueryContractsTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `symbol query request and response bind to a single query descriptor`() {
        val request: KastQueryRequest<KastSymbolQuery> = KastSymbolQueryRequest(query = "processor")
        val response: KastQueryResponse<KastSymbolQuery> = KastSymbolQueryFailureResponse(
            query = "",
            reason = SymbolQueryFailureReason.QUERY_TOO_BROAD,
            message = "query may be empty only when an anchor is provided",
        )

        assertEquals("symbol/query", KastSymbolQuery.method)
        assertTrue(request is KastSymbolQueryRequest)
        assertTrue(response is KastSymbolQueryResponse)
    }

    @Test
    fun `exact and lexical signals share one generic signal shape`() {
        val signals = SymbolQuerySignals(
            exact = SymbolQuerySignal(
                matched = true,
                matches = listOf(
                    SymbolQueryExactMatch(
                        field = "fq_names.fq_name",
                        matchType = "EQUALS",
                        evidence = "lib.Foo",
                    ),
                ),
            ),
            lexical = SymbolQuerySignal(
                matched = true,
                matches = listOf(
                    SymbolQueryLexicalMatch(
                        field = "fq_names.fq_name",
                        term = "foo",
                        matchType = "TOKEN",
                        evidence = "lib.Foo",
                    ),
                ),
            ),
            structural = SymbolQueryStructuralSignal(
                matched = true,
                constraints = listOf(
                    SymbolQueryStructuralConstraint(
                        field = "usageFacets",
                        operator = "ANY",
                        value = JsonArray(listOf(JsonPrimitive("BRIDGE"))),
                    ),
                ),
            ),
            graph = SymbolQueryGraphSignal(matched = false),
            semantic = SymbolQuerySemanticSignal(
                available = false,
                matched = false,
                discoveryOnly = true,
                reason = "Semantic projection index is not configured",
            ),
        )

        val encoded = json.encodeToString(SymbolQuerySignals.serializer(), signals)

        assertTrue(encoded.contains(""""exact":{"matched":true,"matches":[{"field":"fq_names.fq_name","matchType":"EQUALS","evidence":"lib.Foo"}]}"""))
        assertTrue(encoded.contains(""""lexical":{"matched":true,"matches":[{"field":"fq_names.fq_name","term":"foo","matchType":"TOKEN","evidence":"lib.Foo"}]}"""))
    }

    @Test
    fun `symbol query request accepts relevance filter fields`() {
        val request = KastSymbolQueryRequest(
            workspaceRoot = "/workspace",
            query = "processor",
            filters = KastSymbolQueryFilters(
                gradleProject = ":lib",
                relativePathPrefix = "lib/",
                productionOnly = true,
                excludePatterns = listOf("build-logic/**"),
                usageFacets = listOf(SymbolQueryUsageFacet.BRIDGE),
            ),
        )

        val encoded = json.encodeToString(KastSymbolQueryRequest.serializer(), request)
        val decoded = json.decodeFromString(KastSymbolQueryRequest.serializer(), encoded)

        assertTrue(encoded.contains(""""gradleProject":":lib""""))
        assertEquals(":lib", decoded.filters.gradleProject)
        assertEquals("lib/", decoded.filters.relativePathPrefix)
        assertEquals(true, decoded.filters.productionOnly)
        assertEquals(listOf("build-logic/**"), decoded.filters.excludePatterns)
        assertEquals(listOf(SymbolQueryUsageFacet.BRIDGE), decoded.filters.usageFacets)
    }

    @Test
    fun `symbol query declaration accepts usage facet response metadata`() {
        val declaration = SymbolQueryDeclaration(
            fqId = 7,
            fqName = "lib.CardPaymentProcessor",
            simpleName = "CardPaymentProcessor",
            kind = "CLASS",
            visibility = "PUBLIC",
            usageFacets = listOf(
                SymbolQueryUsageFacet.PUBLIC_API,
                SymbolQueryUsageFacet.BRIDGE,
            ),
            modulePath = ":lib",
            sourceSet = "main",
            file = SymbolQueryDeclarationFile(
                prefixId = 2,
                dirPath = "lib",
                filename = "CardPaymentProcessor.kt",
                path = "/workspace/lib/CardPaymentProcessor.kt",
            ),
            declarationOffset = 1,
        )

        val encoded = json.encodeToString(SymbolQueryDeclaration.serializer(), declaration)
        val decoded = json.decodeFromString(SymbolQueryDeclaration.serializer(), encoded)

        assertTrue(encoded.contains(""""usageFacets":["PUBLIC_API","BRIDGE"]"""))
        assertEquals(declaration, decoded)
    }
}
