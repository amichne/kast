package support.delivery

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

        val derived = assertInstanceOf(
            Kvp003GraphProofResult.Complete::class.java,
            deriveKvp003GraphProof(),
        ).proof
        val decoded = assertInstanceOf(
            Kvp003GraphProofResult.Complete::class.java,
            decodeKvp003GraphProof(encodeKvp003GraphProof(derived)),
        ).proof
        assertEquals(graph.order, decoded.graph.order)
        assertEquals(graph.waves, decoded.graph.waves)
        assertEquals(expectedKvp003RejectedCases(), decoded.rejectedCases)

        val changed = encodeKvp003GraphProof(derived)
            .replace("\"selectedLaneJoin\"", "\"flattened\"")
        assertEquals(
            Kvp003GraphProofResult.Rejected(
                Kvp003GraphProofFailure.ORDERING_KINDS_MISMATCH,
            ),
            decodeKvp003GraphProof(changed),
        )
    }

    private fun dependency(result: DeliveryDependencyResult): DeliveryDependency =
        assertInstanceOf(DeliveryDependencyResult.Complete::class.java, result).dependency

    private fun target(result: LifecycleTargetResult): LifecycleTarget =
        assertInstanceOf(LifecycleTargetResult.Complete::class.java, result).target

    private fun complete(result: TypedGraphResult): TypedGraph =
        assertInstanceOf(TypedGraphResult.Complete::class.java, result).graph

    private fun expectedKvp003RejectedCases() = Kvp003RejectedCase.entries.toSet()
}
