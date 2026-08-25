package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@Serializable
internal enum class DeliveryGateGraphOutcome { COMPLETE }

@Serializable
internal enum class DeliveryGateGraphNegativeCase {
    MISSING_DEPENDENCY_RECEIPT,
    DUPLICATE_RECEIPT_OUTPUT,
    MISSING_REGISTERED_TASK,
    UNREPRESENTED_REGISTERED_TASK,
}

internal enum class Kvp006GateGraphProofFailure {
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    COUNT_MISMATCH,
    REGISTERED_TASK_MISMATCH,
    NEGATIVE_CASE_MISMATCH,
    NEGATIVE_FAILURE_MISMATCH,
    GRAPH_REFINEMENT_MISMATCH,
}

@ConsistentCopyVisibility
internal data class Kvp006GateGraphProof internal constructor(
    val completionGateCount: Int,
    val gateCount: Int,
    val greenGateCount: Int,
    val redGateCount: Int,
    val registeredTasks: Set<GradleGateTaskName>,
    val uniqueReceiptOutputCount: Int,
)

@ConsistentCopyVisibility
internal data class Kvp006GateGraphNegativeProof internal constructor(
    val cases: List<DeliveryGateGraphNegativeCase>,
    val failures: List<DeliveryGateGraphFailure>,
)

internal sealed interface Kvp006GateGraphProofResult {
    data class Complete(val proof: Kvp006GateGraphProof) : Kvp006GateGraphProofResult
    data class Rejected(val failure: Kvp006GateGraphProofFailure) : Kvp006GateGraphProofResult
}

internal sealed interface Kvp006GateGraphNegativeProofResult {
    data class Complete(val proof: Kvp006GateGraphNegativeProof) :
        Kvp006GateGraphNegativeProofResult
    data class Rejected(val failure: Kvp006GateGraphProofFailure) :
        Kvp006GateGraphNegativeProofResult
}

@Serializable
private data class DeliveryGateGraphProofDocument(
    val completionGateCount: Int,
    val gateCount: Int,
    val greenGateCount: Int,
    val outcome: DeliveryGateGraphOutcome,
    val redGateCount: Int,
    val registeredTaskCount: Int,
    val registeredTasks: List<String>,
    val schemaVersion: Int,
    val taskId: String,
    val uniqueReceiptOutputCount: Int,
)

@Serializable
private data class DeliveryGateGraphNegativeProofDocument(
    val observedFailures: List<DeliveryGateGraphFailure>,
    val rejectedCases: List<DeliveryGateGraphNegativeCase>,
    val schemaVersion: Int,
    val taskId: String,
)

private val deliveryGateGraphJson = Json { prettyPrint = true }
private val expectedNegativeCases = DeliveryGateGraphNegativeCase.entries.toList()
private val expectedNegativeFailures = listOf(
    DeliveryGateGraphFailure.DEPENDENCY_RECEIPT_MISMATCH,
    DeliveryGateGraphFailure.DUPLICATE_RECEIPT_OUTPUT,
    DeliveryGateGraphFailure.REGISTERED_TASK_MISSING,
    DeliveryGateGraphFailure.UNREPRESENTED_REGISTERED_TASK,
)

private data class DeliveryGateGraphFixture(
    val program: DeliveryProgram,
    val registeredTaskNames: Set<String>,
)

@CacheableTask
abstract class VerifyDeliveryGateGraphTask : DefaultTask() {
    @get:Input abstract val registeredTaskNames: ListProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verifyGateGraph() {
        val admitted = when (val result = admitDeliveryGateGraph(
            KastVfsPassiveReusedIndexProgram.validated.program,
            registeredTaskNames.get().toSet(),
        )) {
            is DeliveryGateGraphAdmission.Admitted -> result.graph
            is DeliveryGateGraphAdmission.Rejected -> throw GradleException(
                "delivery gate graph rejected: ${result.failure}",
            )
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            deliveryGateGraphJson.encodeToString(
                DeliveryGateGraphProofDocument.serializer(),
                admitted.proofDocument(),
            ) + "\n",
        )
    }
}

@CacheableTask
abstract class VerifyDeliveryGateGraphNegativeTask : DefaultTask() {
    @get:Input abstract val registeredTaskNames: ListProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verifyNegativeGateGraph() {
        val program = KastVfsPassiveReusedIndexProgram.validated.program
        val names = registeredTaskNames.get().toSet()
        val greenIndex = program.gates.indexOfFirst { it.kind == GateKind.GREEN }
        val duplicateIndex = 1
        val missingDependency = program.copy(
            gates = program.gates.mapIndexed { index, gate ->
                if (index == greenIndex) gate.copy(dependencyReceiptIds = emptySet()) else gate
            },
        )
        val duplicateOutput = program.copy(
            gates = program.gates.mapIndexed { index, gate ->
                if (index == duplicateIndex) {
                    gate.copy(outputReceiptId = program.gates.first().outputReceiptId)
                } else gate
            },
        )
        val fixtures = listOf(
            DeliveryGateGraphNegativeCase.MISSING_DEPENDENCY_RECEIPT to
                DeliveryGateGraphFixture(missingDependency, names),
            DeliveryGateGraphNegativeCase.DUPLICATE_RECEIPT_OUTPUT to
                DeliveryGateGraphFixture(duplicateOutput, names),
            DeliveryGateGraphNegativeCase.MISSING_REGISTERED_TASK to
                DeliveryGateGraphFixture(program, names - names.sorted().first()),
            DeliveryGateGraphNegativeCase.UNREPRESENTED_REGISTERED_TASK to
                DeliveryGateGraphFixture(program, names + "manualCompletion"),
        )
        val observed = fixtures.mapIndexed { index, (case, fixture) ->
            val failure = when (val result = admitDeliveryGateGraph(
                fixture.program,
                fixture.registeredTaskNames,
            )) {
                is DeliveryGateGraphAdmission.Rejected -> result.failure
                is DeliveryGateGraphAdmission.Admitted -> throw GradleException(
                    "negative gate-graph fixture was admitted: $case",
                )
            }
            if (failure != expectedNegativeFailures[index]) {
                throw GradleException(
                    "$case rejected as $failure instead of ${expectedNegativeFailures[index]}",
                )
            }
            failure
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            deliveryGateGraphJson.encodeToString(
                DeliveryGateGraphNegativeProofDocument.serializer(),
                DeliveryGateGraphNegativeProofDocument(observed, expectedNegativeCases, 1, "KVP-006"),
            ) + "\n",
        )
    }
}

/**
 * Proof transition: canonical delivery program -> `Kvp006GateGraphProofResult`.
 * Establishes the exact canonical task-name set and all admitted graph cardinalities. Expected
 * refinement failure is finite [Kvp006GateGraphProofFailure]; raw task names stay at Gradle edges.
 */
internal fun deriveKvp006GateGraphProof(): Kvp006GateGraphProofResult {
    val program = KastVfsPassiveReusedIndexProgram.validated.program
    val names = mutableSetOf<String>()
    for (gate in program.gates) {
        when (val result = refineGradleGateTaskName(gate)) {
            is GradleGateTaskNameRefinement.Refined -> names += result.name.value
            is GradleGateTaskNameRefinement.Rejected -> return gateGraphProofMismatch()
        }
    }
    val admitted = when (val result = admitDeliveryGateGraph(program, names)) {
        is DeliveryGateGraphAdmission.Admitted -> result.graph
        is DeliveryGateGraphAdmission.Rejected -> return gateGraphProofMismatch()
    }
    val document = admitted.proofDocument()
    return Kvp006GateGraphProofResult.Complete(document.toProof())
}

/**
 * Proof transition: positive report JSON `String -> Kvp006GateGraphProofResult`.
 * Establishes exact schema, identity, counts, outcome, and canonical registered-task membership.
 * Expected malformed or mismatched evidence is finite [Kvp006GateGraphProofFailure].
 */
internal fun decodeKvp006GateGraphProof(raw: String): Kvp006GateGraphProofResult {
    val document = try {
        deliveryGateGraphJson.decodeFromString(DeliveryGateGraphProofDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp006GateGraphProofResult.Rejected(
            Kvp006GateGraphProofFailure.MALFORMED_DOCUMENT,
        )
    }
    val expected = when (val result = deriveKvp006GateGraphProof()) {
        is Kvp006GateGraphProofResult.Complete -> result.proof
        is Kvp006GateGraphProofResult.Rejected -> return result
    }
    val failure = when {
        document.schemaVersion != 1 || document.outcome != DeliveryGateGraphOutcome.COMPLETE ->
            Kvp006GateGraphProofFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-006" -> Kvp006GateGraphProofFailure.TASK_ID_MISMATCH
        document.gateCount != expected.gateCount ||
            document.redGateCount != expected.redGateCount ||
            document.greenGateCount != expected.greenGateCount ||
            document.completionGateCount != expected.completionGateCount ||
            document.uniqueReceiptOutputCount != expected.uniqueReceiptOutputCount ->
            Kvp006GateGraphProofFailure.COUNT_MISMATCH
        document.registeredTaskCount != expected.registeredTasks.size ||
            document.registeredTasks != expected.registeredTasks.map { it.value }.sorted() ->
            Kvp006GateGraphProofFailure.REGISTERED_TASK_MISMATCH
        else -> return Kvp006GateGraphProofResult.Complete(expected)
    }
    return Kvp006GateGraphProofResult.Rejected(failure)
}

/**
 * Proof transition: negative report JSON `String -> Kvp006GateGraphNegativeProofResult`.
 * Establishes all four exact rejected cases and their typed failures. Expected malformed or
 * mismatched evidence is finite [Kvp006GateGraphProofFailure].
 */
internal fun decodeKvp006GateGraphNegativeProof(raw: String):
    Kvp006GateGraphNegativeProofResult {
    val document = try {
        deliveryGateGraphJson.decodeFromString(
            DeliveryGateGraphNegativeProofDocument.serializer(),
            raw,
        )
    } catch (_: SerializationException) {
        return Kvp006GateGraphNegativeProofResult.Rejected(
            Kvp006GateGraphProofFailure.MALFORMED_DOCUMENT,
        )
    }
    val failure = when {
        document.schemaVersion != 1 -> Kvp006GateGraphProofFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-006" -> Kvp006GateGraphProofFailure.TASK_ID_MISMATCH
        document.rejectedCases != expectedNegativeCases ->
            Kvp006GateGraphProofFailure.NEGATIVE_CASE_MISMATCH
        document.observedFailures != expectedNegativeFailures ->
            Kvp006GateGraphProofFailure.NEGATIVE_FAILURE_MISMATCH
        else -> return Kvp006GateGraphNegativeProofResult.Complete(
            Kvp006GateGraphNegativeProof(expectedNegativeCases, expectedNegativeFailures),
        )
    }
    return Kvp006GateGraphNegativeProofResult.Rejected(failure)
}

private fun AdmittedDeliveryGateGraph.proofDocument() = DeliveryGateGraphProofDocument(
    completionGateCount = gates.count { it.kind == GateKind.TASK_COMPLETION },
    gateCount = gates.size,
    greenGateCount = gates.count { it.kind == GateKind.GREEN },
    outcome = DeliveryGateGraphOutcome.COMPLETE,
    redGateCount = gates.count { it.kind == GateKind.RED },
    registeredTaskCount = registeredTasks.size,
    registeredTasks = registeredTasks.map { it.value }.sorted(),
    schemaVersion = 1,
    taskId = "KVP-006",
    uniqueReceiptOutputCount = gates.map { it.outputReceiptId }.toSet().size,
)

private fun DeliveryGateGraphProofDocument.toProof() = Kvp006GateGraphProof(
    completionGateCount,
    gateCount,
    greenGateCount,
    redGateCount,
    registeredTasks.mapTo(mutableSetOf(), ::GradleGateTaskName),
    uniqueReceiptOutputCount,
)

private fun gateGraphProofMismatch() = Kvp006GateGraphProofResult.Rejected(
    Kvp006GateGraphProofFailure.GRAPH_REFINEMENT_MISMATCH,
)
