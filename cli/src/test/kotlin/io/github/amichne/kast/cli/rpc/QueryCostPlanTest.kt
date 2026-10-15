package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QueryCostPlanTest {
    @Test
    fun `plan preserves admitted run and names workspace expansion before semantic execution`() {
        val bytes = example("traceAffectedUses")
        val plan = assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(bytes))
        assertEquals(admittedRun(bytes), plan.request)
        assertEquals(
            listOf(QueryCostAdvice.SET_EXPANSION_DOMAIN, QueryCostAdvice.TRACE_HAS_MULTIPLE_RELATION_PHASES),
            plan.advice,
        )
    }

    @Test
    fun `exact scoped discovery needs no broad discovery advice`() {
        val bytes = example("scopedExactDeclaration")
        val plan = assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(bytes))
        assertEquals(admittedRun(bytes), plan.request)
        assertTrue(plan.advice.isEmpty())
    }

    @Test
    fun `late filters remain distinct from source scope and name selection`() {
        val plan =
            assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(example("publicFunctionInventory")))
        assertEquals(
            listOf(QueryCostAdvice.USE_EXACT_NAME_OR_REFERENCE, QueryCostAdvice.LATE_FILTER_DOES_NOT_NARROW_DISCOVERY),
            plan.advice,
        )
    }

    @Test
    fun `defaults expose broad discovery and declaration families without predicting measured cost`() {
        val plan = assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(example("runByName")))
        assertEquals(
            listOf(QueryCostAdvice.NARROW_DISCOVERY_SCOPE, QueryCostAdvice.RESTRICT_DECLARATION_KINDS),
            plan.advice,
        )
        val encoded = Json.parseToJsonElement(Json.encodeToString<QueryCostPlanReply>(plan)).jsonObject
        assertEquals(setOf("type", "request", "advice", "basis", "estimate"), encoded.keys)
        assertEquals("planned", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("REQUEST_SHAPE_ONLY", encoded.getValue("basis").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", encoded.getValue("estimate").jsonPrimitive.content)
        assertEquals(2, encoded.getValue("advice").jsonArray.size)
    }

    @Test
    fun `explicit source domain does not inherit workspace expansion advice`() {
        val plan =
            assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(example("narrowReferenceExpansion")))
        assertTrue(plan.advice.isEmpty())
    }

    @Test
    fun `bounded walk keeps its depth while workspace expansion remains explicit`() {
        val bounded =
            assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(example("boundedCallerWalk")))
        assertTrue(bounded.advice.isEmpty())
        val workspace =
            assertInstanceOf(QueryCostPlanReply.Planned::class.java, planToolQuery(example("workspaceReferenceWalk")))
        assertEquals(
            listOf(QueryCostAdvice.SET_EXPANSION_DOMAIN),
            workspace.advice,
        )
    }

    @Test
    fun `exhaustive extent adds a distinct advice without changing the requested scope`() {
        val original = admittedRun(example("workspaceReferenceWalk"))
        val walk = original.steps.values.single() as QueryStepDocument.Walk
        val steps =
            BoundedProtocolList.create<QueryStepDocument>(
                listOf(walk.copy(extent = TraversalExtentDocument.Exhaustive))
            ) as Refinement.Refined
        val exhaustive = original.copy(steps = steps.value)
        assertEquals(
            listOf(QueryCostAdvice.SET_EXPANSION_DOMAIN, QueryCostAdvice.EXHAUSTIVE_WALK_HAS_NO_DEPTH_LIMIT),
            exhaustive.costAdvice(),
        )
        assertEquals(walk.expansionScope, (exhaustive.steps.values.single() as QueryStepDocument.Walk).expansionScope)
    }

    @Test
    fun `source reads retained reads and resumes do not pretend to plan a run`() {
        for (name in listOf("readCallbackSource", "readRetained", "resumeQuery")) {
            assertEquals(
                QueryCostPlanReply.Rejected(QueryCostPlanFailure.RUN_REQUIRED),
                planToolQuery(example(name)),
                name,
            )
        }
    }

    @Test
    fun `malformed oversized and schema-invalid inputs fail before advice`() {
        for (bytes in
            listOf(
                "{".toByteArray(),
                Json.encodeToString(MissingPlannerRequest).toByteArray(),
                byteArrayOf(0xC3.toByte(), 0x28),
            )) {
            assertEquals(QueryCostPlanReply.Rejected(QueryCostPlanFailure.INVALID_ARGUMENTS), planToolQuery(bytes))
        }
        assertEquals(
            QueryCostPlanReply.Rejected(QueryCostPlanFailure.REQUEST_TOO_LARGE),
            planToolQuery(ByteArray(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)),
        )
        val rejected =
            Json.parseToJsonElement(
                    Json.encodeToString<QueryCostPlanReply>(
                        QueryCostPlanReply.Rejected(QueryCostPlanFailure.RUN_REQUIRED)
                    )
                )
                .jsonObject
        assertEquals(setOf("type", "failure"), rejected.keys)
        assertEquals("rejected", rejected.getValue("type").jsonPrimitive.content)
        assertEquals("RUN_REQUIRED", rejected.getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun `real entry plans from an empty home without installation or project discovery`(@TempDir temporary: Path) {
        val home = Files.createDirectory(temporary.resolve("home"))
        val stdout = temporary.resolve("stdout.json")
        val stderr = temporary.resolve("stderr.txt")
        val child =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.home=$home",
                    "-cp",
                    System.getProperty("java.class.path"),
                    KastToolRpcMain::class.java.name,
                    "plan-query",
                )
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        try {
            val bytes = example("traceAffectedUses")
            child.outputStream.use { it.write(bytes) }
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Request-only planner did not finish")
            assertEquals(0, child.exitValue(), Files.readString(stderr))
            assertEquals(planToolQuery(bytes), Json.decodeFromString<QueryCostPlanReply>(Files.readString(stdout)))
            Files.list(home).use { assertEquals(0, it.count()) }
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun example(name: String): ByteArray =
        PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
            .examples
            .getValue(name)
            .value
            .toString()
            .toByteArray()

    private fun admittedRun(bytes: ByteArray): QueryRunRequest.Run {
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject,
            ) as Refinement.Refined
        return (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
    }
}

@Serializable private data object MissingPlannerRequest
