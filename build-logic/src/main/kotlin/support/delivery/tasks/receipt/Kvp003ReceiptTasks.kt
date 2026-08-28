package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp003RejectedCase {
    SELECTED_LANE_NOT_MEMBER,
    CYCLE,
    MISSING_RETIREMENT_TARGET,
    RECOVERY_DEAD_END,
}

internal enum class Kvp003GraphProofFailure {
    REFINEMENT_MISMATCH,
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    OUTCOME_MISMATCH,
    REJECTED_CASES_MISMATCH,
    TASK_ORDER_MISMATCH,
    WAVES_MISMATCH,
    ORDERING_KINDS_MISMATCH,
    LIFECYCLE_KINDS_MISMATCH,
}

@ConsistentCopyVisibility
internal data class Kvp003GraphProof internal constructor(
    val rejectedCases: Set<Kvp003RejectedCase>,
    val graph: TypedGraph,
)

internal sealed interface Kvp003GraphProofResult {
    data class Complete(val proof: Kvp003GraphProof) : Kvp003GraphProofResult
    data class Rejected(val failure: Kvp003GraphProofFailure) : Kvp003GraphProofResult
}

@Serializable
private data class Kvp003GraphProofJsonDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: String,
    val rejectedCases: List<String>,
    val taskOrder: List<String>,
    val waves: Map<String, Int>,
    val orderingKinds: List<String>,
    val lifecycleKinds: List<String>,
)

private val kvp003ProofJson = Json { ignoreUnknownKeys = false; prettyPrint = true }
private val expectedKvp003RejectedCases = Kvp003RejectedCase.entries.toSet()
private val expectedKvp003TaskOrder = listOf(
    "KVP-101", "KVP-102", "KVP-103", "KVP-104", "KVP-105",
)
private val expectedKvp003Waves = mapOf(
    "KVP-101" to 0,
    "KVP-102" to 1,
    "KVP-103" to 0,
    "KVP-104" to 1,
    "KVP-105" to 2,
)
private val expectedKvp003OrderingKinds = listOf(
    "allOf", "oneOf", "root", "selectedLaneJoin",
)
private val expectedKvp003LifecycleKinds = listOf(
    "invalidation", "recovery", "retirement",
)

/**
 * Proof transition: current typed graph operations -> `Kvp003GraphProofResult`.
 * Establishes all four rejected graph cases and the canonical ordering/lifecycle fixture. Expected
 * mismatch is finite [Kvp003GraphProofFailure]; raw projections leave only at the report boundary.
 */
internal fun deriveKvp003GraphProof(): Kvp003GraphProofResult {
    val k101 = TaskId("KVP-101")
    val k102 = TaskId("KVP-102")
    val k103 = TaskId("KVP-103")
    val k104 = TaskId("KVP-104")
    val k105 = TaskId("KVP-105")
    if (refineSelectedLaneJoin(setOf(k101, k102), k103) !=
        DeliveryDependencyResult.Rejected(DeliveryGraphFailure.SELECTED_LANE_NOT_MEMBER)
    ) return mismatch()
    if (refineLifecycleTarget("") !=
        LifecycleTargetResult.Rejected(DeliveryGraphFailure.MISSING_LIFECYCLE_TARGET)
    ) return mismatch()

    val from101 = when (val result = refineAllOf(setOf(k101))) {
        is DeliveryDependencyResult.Complete -> result.dependency
        is DeliveryDependencyResult.Rejected -> return mismatch()
    }
    val from102 = when (val result = refineAllOf(setOf(k102))) {
        is DeliveryDependencyResult.Complete -> result.dependency
        is DeliveryDependencyResult.Rejected -> return mismatch()
    }
    val cycle = refineTypedGraph(
        listOf(GraphTask(k101, from102), GraphTask(k102, from101)),
        emptyList(),
        k102,
    )
    if (cycle != TypedGraphResult.Rejected(DeliveryGraphFailure.CYCLE)) return mismatch()
    val recovery = refineTypedGraph(
        listOf(
            GraphTask(k101, DeliveryDependency.Root),
            GraphTask(k102, from101),
            GraphTask(k103, DeliveryDependency.Root),
        ),
        listOf(LifecycleEdge.Recovery(k101, k103, k103)),
        k102,
    )
    if (recovery != TypedGraphResult.Rejected(DeliveryGraphFailure.RECOVERY_DEAD_END)) {
        return mismatch()
    }

    val graph = when (val result = canonicalGraph(k101, k102, k103, k104, k105)) {
        is TypedGraphResult.Complete -> result.graph
        is TypedGraphResult.Rejected -> return mismatch()
    }
    if (graph.order.map { it.value } != expectedKvp003TaskOrder ||
        graph.waves.mapKeys { it.key.value } != expectedKvp003Waves ||
        graph.tasks.values.map { it.dependency.projectionName() }.distinct().sorted() !=
        expectedKvp003OrderingKinds ||
        graph.lifecycleEdges.map { it.projectionName() }.distinct().sorted() !=
        expectedKvp003LifecycleKinds
    ) return mismatch()
    return Kvp003GraphProofResult.Complete(
        Kvp003GraphProof(expectedKvp003RejectedCases, graph),
    )
}

/**
 * Proof transition: canonical task identities -> `TypedGraphResult`.
 * Establishes the KVP-003 proof fixture through the public refinements. Expected failure remains
 * closed as [DeliveryGraphFailure]; raw collections stay within this report owner.
 */
private fun canonicalGraph(
    k101: TaskId,
    k102: TaskId,
    k103: TaskId,
    k104: TaskId,
    k105: TaskId,
): TypedGraphResult {
    val allOf = when (val result = refineAllOf(setOf(k101))) {
        is DeliveryDependencyResult.Complete -> result.dependency
        is DeliveryDependencyResult.Rejected -> return TypedGraphResult.Rejected(result.failure)
    }
    val oneOf = when (val result = refineOneOf(setOf(k102, k103))) {
        is DeliveryDependencyResult.Complete -> result.dependency
        is DeliveryDependencyResult.Rejected -> return TypedGraphResult.Rejected(result.failure)
    }
    val selected = when (val result = refineSelectedLaneJoin(setOf(k102, k104), k104)) {
        is DeliveryDependencyResult.Complete -> result.dependency
        is DeliveryDependencyResult.Rejected -> return TypedGraphResult.Rejected(result.failure)
    }
    val target = when (val result = refineLifecycleTarget("LEGACY_INDEXER")) {
        is LifecycleTargetResult.Complete -> result.target
        is LifecycleTargetResult.Rejected -> return TypedGraphResult.Rejected(result.failure)
    }
    return refineTypedGraph(
        listOf(
            GraphTask(k101, DeliveryDependency.Root),
            GraphTask(k102, allOf),
            GraphTask(k103, DeliveryDependency.Root),
            GraphTask(k104, oneOf),
            GraphTask(k105, selected),
        ),
        listOf(
            LifecycleEdge.Retirement(k105, target),
            LifecycleEdge.Invalidation(k104, k102),
            LifecycleEdge.Recovery(k102, k103, k104),
        ),
        k105,
    )
}

private fun mismatch() =
    Kvp003GraphProofResult.Rejected(Kvp003GraphProofFailure.REFINEMENT_MISMATCH)

private fun DeliveryDependency.projectionName() = when (this) {
    DeliveryDependency.Root -> "root"
    is DeliveryDependency.AllOf -> "allOf"
    is DeliveryDependency.OneOf -> "oneOf"
    is DeliveryDependency.SelectedLaneJoin -> "selectedLaneJoin"
}

private fun LifecycleEdge.projectionName() = when (this) {
    is LifecycleEdge.Retirement -> "retirement"
    is LifecycleEdge.Invalidation -> "invalidation"
    is LifecycleEdge.Recovery -> "recovery"
}

/**
 * Proof transition: `Kvp003GraphProof -> String`.
 * Preserves every graph proof field in generated JSON. No expected failure exists after
 * refinement; raw JSON is emitted only at the Gradle report boundary.
 */
internal fun encodeKvp003GraphProof(proof: Kvp003GraphProof): String {
    val graph = proof.graph
    return kvp003ProofJson.encodeToString(
        Kvp003GraphProofJsonDocument.serializer(),
        Kvp003GraphProofJsonDocument(
            schemaVersion = 1,
            taskId = "KVP-003",
            outcome = "COMPLETE",
            rejectedCases = proof.rejectedCases.map { it.name }.sorted(),
            taskOrder = graph.order.map { it.value },
            waves = graph.waves.entries.associate { it.key.value to it.value },
            orderingKinds = graph.tasks.values.map {
                it.dependency.projectionName()
            }.distinct().sorted(),
            lifecycleKinds = graph.lifecycleEdges.map { it.projectionName() }.distinct().sorted(),
        ),
    ) + "\n"
}

/**
 * Proof transition: report JSON `String -> Kvp003GraphProofResult`.
 * Establishes exact schema, identity, negative cases, ordering, waves, and lifecycle kinds. Expected
 * malformed or mismatched evidence is finite [Kvp003GraphProofFailure]; raw JSON stays here.
 */
internal fun decodeKvp003GraphProof(raw: String): Kvp003GraphProofResult {
    val document = try {
        kvp003ProofJson.decodeFromString(Kvp003GraphProofJsonDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp003GraphProofResult.Rejected(Kvp003GraphProofFailure.MALFORMED_DOCUMENT)
    }
    val failure = when {
        document.schemaVersion != 1 -> Kvp003GraphProofFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-003" -> Kvp003GraphProofFailure.TASK_ID_MISMATCH
        document.outcome != "COMPLETE" -> Kvp003GraphProofFailure.OUTCOME_MISMATCH
        document.rejectedCases != expectedKvp003RejectedCases.map { it.name }.sorted() ->
            Kvp003GraphProofFailure.REJECTED_CASES_MISMATCH
        document.taskOrder != expectedKvp003TaskOrder ->
            Kvp003GraphProofFailure.TASK_ORDER_MISMATCH
        document.waves != expectedKvp003Waves -> Kvp003GraphProofFailure.WAVES_MISMATCH
        document.orderingKinds != expectedKvp003OrderingKinds ->
            Kvp003GraphProofFailure.ORDERING_KINDS_MISMATCH
        document.lifecycleKinds != expectedKvp003LifecycleKinds ->
            Kvp003GraphProofFailure.LIFECYCLE_KINDS_MISMATCH
        else -> return deriveKvp003GraphProof()
    }
    return Kvp003GraphProofResult.Rejected(failure)
}

abstract class Kvp003ReceiptTaskBase : Kvp002ReceiptTaskBase() {
    @get:Input abstract val graphTaskId: Property<String>
    @get:Input abstract val graphRedGateId: Property<String>
    @get:Input abstract val graphGreenGateId: Property<String>
    @get:Input abstract val graphCompletionGateId: Property<String>
    @get:Input abstract val graphRedReceiptId: Property<String>
    @get:Input abstract val graphGreenReceiptId: Property<String>
    @get:Input abstract val graphCompletionReceiptId: Property<String>
    @get:Input abstract val graphRedCommand: Property<String>
    @get:Input abstract val graphGreenCommand: Property<String>
    @get:Input abstract val graphCompletionCommand: Property<String>
    @get:Input abstract val graphTaskInputDigest: Property<String>
    @get:Input abstract val graphCompletionInputDigest: Property<String>
    @get:Input abstract val graphProofReportPath: Property<String>
    @get:InputFile abstract val predecessorRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val predecessorGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val predecessorProofReportFile: RegularFileProperty
    @get:InputFile abstract val predecessorCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-003 command plus fixed test filter -> successful gate process.
     * Establishes exact command equality and zero exit; mismatch is [ProofReceiptFailure]. Raw
     * process arguments are exposed only to Gradle's process boundary.
     */
    internal fun runGraphGate(command: String, filter: String) {
        val expected = "./gradlew :build-logic:test --tests \"$filter\""
        if (command != expected) {
            rejectReceipt("KVP-003 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", ":build-logic:test", "--tests", filter)
        }
    }

    /**
     * Proof transition: configured graph inputs plus `AuthorityGitRevision` ->
     * `Kvp003ReceiptContexts`. Establishes the admitted KVP-002 closure; expected receipt failures
     * stay closed until the Gradle boundary renders them. Raw Gradle properties remain here.
     */
    internal fun graphContexts(head: AuthorityGitRevision): Kvp003ReceiptContexts {
        val predecessor = contexts(head)
        val proof = predecessor.reportProof()
        val red = predecessor.boundary.admit(
            predecessorRedReceiptFile.get().asFile.toPath(),
            predecessor.redExpectation(proof),
        )
        val green = predecessor.boundary.admit(
            predecessorGreenReceiptFile.get().asFile.toPath(),
            predecessor.greenExpectation(red, proof),
        )
        val completion = predecessor.boundary.admit(
            predecessorCompletionReceiptFile.get().asFile.toPath(),
            predecessor.completionExpectation(red, green),
        )
        return Kvp003ReceiptContexts(
            predecessor.boundary,
            completion,
            graphTaskId.get(),
            graphRedGateId.get(),
            graphGreenGateId.get(),
            graphCompletionGateId.get(),
            graphRedReceiptId.get(),
            graphGreenReceiptId.get(),
            graphCompletionReceiptId.get(),
            graphRedCommand.get(),
            graphGreenCommand.get(),
            graphCompletionCommand.get(),
            graphTaskInputDigest.get(),
            graphCompletionInputDigest.get(),
            graphProofReportPath.get(),
        )
    }
}
