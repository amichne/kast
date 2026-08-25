package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DeliveryGraphNegativeTest {
    @Test
    fun `selected lane must belong to its candidate set`() {
        assertEquals(
            DeliveryDependencyResult.Rejected(DeliveryGraphFailure.SELECTED_LANE_NOT_MEMBER),
            refineSelectedLaneJoin(
                candidates = setOf(TaskId("KVP-101"), TaskId("KVP-102")),
                selected = TaskId("KVP-103"),
            ),
        )
    }

    @Test
    fun `cycles reject as finite graph failure`() {
        val graph = refineTypedGraph(
            tasks = listOf(
                GraphTask(TaskId("KVP-101"), complete(refineAllOf(setOf(TaskId("KVP-102"))))),
                GraphTask(TaskId("KVP-102"), complete(refineAllOf(setOf(TaskId("KVP-101"))))),
            ),
            lifecycleEdges = emptyList(),
            terminal = TaskId("KVP-102"),
        )

        assertEquals(TypedGraphResult.Rejected(DeliveryGraphFailure.CYCLE), graph)
    }

    @Test
    fun `missing retirement target rejects before edge construction`() {
        assertEquals(
            LifecycleTargetResult.Rejected(DeliveryGraphFailure.MISSING_LIFECYCLE_TARGET),
            refineLifecycleTarget(""),
        )
    }

    @Test
    fun `recovery must resume on a path to the terminal`() {
        val graph = refineTypedGraph(
            tasks = listOf(
                GraphTask(TaskId("KVP-101"), DeliveryDependency.Root),
                GraphTask(TaskId("KVP-102"), complete(refineAllOf(setOf(TaskId("KVP-101"))))),
                GraphTask(TaskId("KVP-103"), DeliveryDependency.Root),
            ),
            lifecycleEdges = listOf(
                LifecycleEdge.Recovery(
                    failed = TaskId("KVP-101"),
                    recovery = TaskId("KVP-103"),
                    resumesAt = TaskId("KVP-103"),
                ),
            ),
            terminal = TaskId("KVP-102"),
        )

        assertEquals(TypedGraphResult.Rejected(DeliveryGraphFailure.RECOVERY_DEAD_END), graph)
    }

    private fun complete(result: DeliveryDependencyResult): DeliveryDependency =
        assertInstanceOf(DeliveryDependencyResult.Complete::class.java, result).dependency
}

class DeliveryGraphTest {
    @Test
    fun `ordering waves and lifecycle edges derive independently`() {
        val graph = complete(
            refineTypedGraph(
                tasks = listOf(
                    GraphTask(TaskId("KVP-101"), DeliveryDependency.Root),
                    GraphTask(
                        TaskId("KVP-102"),
                        dependency(refineAllOf(setOf(TaskId("KVP-101")))),
                    ),
                    GraphTask(TaskId("KVP-103"), DeliveryDependency.Root),
                    GraphTask(
                        TaskId("KVP-104"),
                        dependency(refineOneOf(setOf(TaskId("KVP-102"), TaskId("KVP-103")))),
                    ),
                    GraphTask(
                        TaskId("KVP-105"),
                        dependency(
                            refineSelectedLaneJoin(
                                setOf(TaskId("KVP-102"), TaskId("KVP-104")),
                                TaskId("KVP-104"),
                            ),
                        ),
                    ),
                ),
                lifecycleEdges = listOf(
                    LifecycleEdge.Retirement(
                        TaskId("KVP-105"),
                        target(refineLifecycleTarget("LEGACY_INDEXER")),
                    ),
                    LifecycleEdge.Invalidation(TaskId("KVP-104"), TaskId("KVP-102")),
                    LifecycleEdge.Recovery(
                        failed = TaskId("KVP-102"),
                        recovery = TaskId("KVP-103"),
                        resumesAt = TaskId("KVP-104"),
                    ),
                ),
                terminal = TaskId("KVP-105"),
            ),
        )

        assertEquals(
            listOf("KVP-101", "KVP-102", "KVP-103", "KVP-104", "KVP-105"),
            graph.order.map { it.value },
        )
        assertEquals(
            mapOf("KVP-101" to 0, "KVP-102" to 1, "KVP-103" to 0, "KVP-104" to 1, "KVP-105" to 2),
            graph.waves.mapKeys { it.key.value },
        )
        assertEquals(3, graph.lifecycleEdges.size)

        writeProofReport(graph)
    }

    private fun dependency(result: DeliveryDependencyResult): DeliveryDependency =
        assertInstanceOf(DeliveryDependencyResult.Complete::class.java, result).dependency

    private fun target(result: LifecycleTargetResult): LifecycleTarget =
        assertInstanceOf(LifecycleTargetResult.Complete::class.java, result).target

    private fun complete(result: TypedGraphResult): TypedGraph =
        assertInstanceOf(TypedGraphResult.Complete::class.java, result).graph

    private fun writeProofReport(graph: TypedGraph) {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val root = if (Files.isDirectory(workingDirectory.resolve("gradle/delivery"))) {
            workingDirectory
        } else {
            workingDirectory.parent
        }
        require(Files.isDirectory(root.resolve("gradle/delivery")))
        val output = root.resolve("build/reports/delivery/KVP-003-graph.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, graphProofJson.encodeToString(DeliveryGraphProofDocument.serializer(), graph.toProof()) + "\n")
    }

    private fun TypedGraph.toProof() = DeliveryGraphProofDocument(
        schemaVersion = 1,
        taskId = "KVP-003",
        outcome = "COMPLETE",
        taskOrder = order.map { it.value },
        waves = waves.entries.associate { it.key.value to it.value },
        orderingKinds = tasks.values.map { it.dependency.projectionName() }.distinct().sorted(),
        lifecycleKinds = lifecycleEdges.map { it.projectionName() }.distinct().sorted(),
    )

    private fun DeliveryDependency.projectionName(): String = when (this) {
        DeliveryDependency.Root -> "root"
        is DeliveryDependency.AllOf -> "allOf"
        is DeliveryDependency.OneOf -> "oneOf"
        is DeliveryDependency.SelectedLaneJoin -> "selectedLaneJoin"
    }

    private fun LifecycleEdge.projectionName(): String = when (this) {
        is LifecycleEdge.Retirement -> "retirement"
        is LifecycleEdge.Invalidation -> "invalidation"
        is LifecycleEdge.Recovery -> "recovery"
    }

    private companion object {
        val graphProofJson = Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }
    }
}

@Serializable
private data class DeliveryGraphProofDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: String,
    val taskOrder: List<String>,
    val waves: Map<String, Int>,
    val orderingKinds: List<String>,
    val lifecycleKinds: List<String>,
)
