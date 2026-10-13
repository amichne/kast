package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Reads authored recipes so admission proof does not depend on generation having run first. */
class PublicToolRecipeTest {
    private val tools =
        requireNotNull(javaClass.getResourceAsStream("tools.schema.json")).bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("tools").jsonArray
        }

    private fun examples(identity: PublicToolIdentity, key: String = "examples"): JsonObject =
        tools
            .single { it.jsonObject.getValue("name").jsonPrimitive.content == identity.toolName }
            .jsonObject
            .getValue(key)
            .jsonObject

    private fun query(name: String): QueryRunRequest {
        val raw = examples(PublicToolIdentity.QUERY_SYMBOLS).getValue(name).jsonObject.getValue("value")
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw)
        assertTrue(admitted is Refinement.Refined, "$name: $admitted")
        return ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
    }

    @Test
    fun `authored recipe inventory passes production schema and typed admission`() {
        assertEquals(4, tools.size)
        assertEquals(29, PublicToolIdentity.entries.sumOf { examples(it).size })
        assertEquals(28, PublicToolIdentity.entries.sumOf { examples(it, "invalidExamples").size })
        PublicToolIdentity.entries.forEach { identity ->
            examples(identity).forEach { (name, example) ->
                val raw = example.jsonObject.getValue("value")
                assertTrue(PublicToolContract.schema(identity).admit(raw) is Validation.Validated, name)
                assertTrue(PublicToolContract.admit(identity, raw) is Refinement.Refined, name)
            }
            examples(identity, "invalidExamples").forEach { (name, example) ->
                assertTrue(
                    PublicToolContract.admit(identity, example.jsonObject.getValue("value")) is Refinement.Rejected,
                    name,
                )
            }
        }
    }

    @Test
    fun `public inventory keeps recursive source scope selected fields and predicate order`() {
        val run = query("publicFunctionInventory") as QueryRunRequest.Run
        val source = run.from as QueryFromDocument.Symbols
        assertEquals(QueryMatchDocument.All, source.match)
        assertEquals(listOf(QueryDeclarationKindDocument.FUNCTION), source.declarationKinds.values)
        assertEquals(listOf("main"), source.scope.sourceSets.values.map { it.value })
        assertEquals("services/orders", source.scope.directory!!.path.value)
        assertEquals(QueryContainmentDocument.DESCENDANTS, source.scope.directory!!.containment)
        assertNull(source.scope.packageName)
        assertEquals(
            listOf(
                QueryStepDocument.Where(
                    QueryPredicateDocument.Visibility(bounded(listOf(QueryVisibilityDocument.PUBLIC)))
                ),
                QueryStepDocument.Where(
                    QueryPredicateDocument.Primitive(
                        QueryPrimitiveFieldDocument.NAME,
                        QueryPrimitiveOperatorDocument.STARTS_WITH,
                        text("create"),
                    )
                ),
            ),
            run.steps.values,
        )
        assertSymbolFields(run)
        assertEquals(50, run.executionBudget!!.maxResults!!.value)
        assertEquals(QueryRetentionModeDocument.DISCARD, run.retention)
    }

    @Test
    fun `bounded caller walk keeps depth breadth first seed scope and presentation limit`() {
        val run = query("boundedCallerWalk") as QueryRunRequest.Run
        assertSelectedReference(run)
        assertEquals(
            listOf(
                QueryStepDocument.Walk(
                    RelationKindDocument.CALLERS,
                    (ProtocolCount.parse(2) as Refinement.Refined).value,
                    TraversalStrategyDocument.BreadthFirst,
                    QueryExpansionScopeDocument.RetainedSeed,
                )
            ),
            run.steps.values,
        )
        assertEquals(QueryOutputDocument.TraversalRecords, run.output)
        assertEquals(50, run.executionBudget!!.maxResults!!.value)
        assertEquals(QueryRetentionModeDocument.DISCARD, run.retention)
    }

    @Test
    fun `selected reference recipes preserve signatures relation order and trace retention`() {
        listOf(
                "directCallers" to RelationKindDocument.CALLERS,
                "implementations" to RelationKindDocument.IMPLEMENTATIONS,
            )
            .forEach { (name, relation) ->
                val run = query(name) as QueryRunRequest.Run
                assertSelectedReference(run)
                assertSymbolFields(run)
                assertEquals(
                    listOf(
                        QueryStepDocument.Related(relation, QueryExpansionScopeDocument.Workspace),
                        QueryStepDocument.Distinct,
                    ),
                    run.steps.values,
                )
                assertEquals(50, run.executionBudget!!.maxResults!!.value)
            }
        val trace = query("traceAffectedUses") as QueryRunRequest.Run
        assertSelectedReference(trace)
        assertSymbolFields(trace)
        assertEquals(listOf(QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace)), trace.steps.values)
        assertEquals(QueryRetentionModeDocument.RETAIN, trace.retention)
        assertEquals(30000L, trace.executionBudget!!.maxElapsedMillis!!.value)
    }

    @Test
    fun `resume recipe preserves output token syntax and presentation limit`() {
        val resume = query("resumeQuery") as QueryRunRequest.Resume
        assertEquals("query-output:v1:00000000-0000-0000-0000-000000000000", resume.continuation.value)
        assertTrue(resume.continuation is QueryExecutionContinuation.Output)
        assertEquals(50, resume.executionBudget!!.maxResults!!.value)
    }

    @Test
    fun `complete impact findings uses fixed completion and preserves meaningful empty inputs`() {
        val run = query("completeImpactFindings") as QueryRunRequest.Run
        assertEquals(
            QueryCompletionDocument(
                QueryCompletionTypeDocument.COMPLETE_ONLY,
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
            ),
            QueryQuestionDocument.from(run).completion,
        )
        assertTrue(run.steps.values.isEmpty())
        assertEquals(QueryRetentionModeDocument.RETAIN, run.retention)
        assertEquals(QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.FINDINGS), run.output)
        val impact = (run.from as QueryFromDocument.Impact).investigation
        assertTrue(impact.declarations.values.isEmpty())
        assertTrue(impact.models.values.isEmpty())
    }

    private fun assertSelectedReference(run: QueryRunRequest.Run) {
        assertEquals(
            listOf("<exact-ref-returned-by-Kast>"),
            (run.from as QueryFromDocument.References).values.values.map { it.token.value },
        )
    }

    private fun assertSymbolFields(run: QueryRunRequest.Run) {
        assertEquals(
            listOf(
                QuerySymbolFieldDocument.NAME,
                QuerySymbolFieldDocument.LOCATION,
                QuerySymbolFieldDocument.SIGNATURE,
            ),
            (run.output as QueryOutputDocument.Symbols).fields.values,
        )
    }

    private fun text(value: String): ProtocolText = (ProtocolText.parse(value) as Refinement.Refined).value

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        (BoundedProtocolList.create(values) as Refinement.Refined).value
}
