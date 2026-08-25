package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
            deliveryGateGraphJson.encodeToString(admitted.proofDocument()) + "\n",
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
        val expected = listOf(
            DeliveryGateGraphFailure.DEPENDENCY_RECEIPT_MISMATCH,
            DeliveryGateGraphFailure.DUPLICATE_RECEIPT_OUTPUT,
            DeliveryGateGraphFailure.REGISTERED_TASK_MISSING,
            DeliveryGateGraphFailure.UNREPRESENTED_REGISTERED_TASK,
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
            if (failure != expected[index]) {
                throw GradleException("$case rejected as $failure instead of ${expected[index]}")
            }
            failure
        }
        writeTextAtomically(
            reportFile.get().asFile.toPath(),
            deliveryGateGraphJson.encodeToString(
                DeliveryGateGraphNegativeProofDocument(
                    observed,
                    fixtures.map { it.first },
                    1,
                    "KVP-006",
                ),
            ) + "\n",
        )
    }
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
