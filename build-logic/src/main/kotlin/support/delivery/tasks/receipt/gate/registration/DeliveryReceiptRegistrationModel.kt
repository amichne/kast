package support.delivery

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

internal data class TaskReceiptRegistration(
    val task: TaskNode,
    val redGate: GateNode,
    val greenGate: GateNode,
    val completionGate: GateNode,
    val redReceipt: Provider<RegularFile>,
    val greenReceipt: Provider<RegularFile>,
    val completionReceipt: RegularFile,
    val proofReport: RegularFile,
    val taskInputDigest: String,
    val completionInputDigest: String,
)

/**
 * Proof transition: validated `DeliveryProgram` plus canonical task identity ->
 * `TaskReceiptRegistration`.
 * Establishes the task's exact gates, receipt outputs, proof output, and input digests. Program
 * validation owns absence and identity failure; raw Gradle files remain at this registration edge.
 */
internal fun Project.taskReceiptRegistration(
    program: DeliveryProgram,
    taskId: TaskId,
): TaskReceiptRegistration {
    val task = program.tasks.single { it.id == taskId }
    val redGate = program.gates.single { it.id == task.red.gateId }
    val greenGate = program.gates.single { it.id == task.green.gateId }
    val completionGate = program.gates.single {
        it.taskId == task.id && it.kind == GateKind.TASK_COMPLETION
    }
    val receiptDirectory = layout.buildDirectory.dir("reports/delivery/receipts")
    return TaskReceiptRegistration(
        task,
        redGate,
        greenGate,
        completionGate,
        receiptDirectory.map { it.file("${redGate.outputReceiptId}.receipt.json") },
        receiptDirectory.map { it.file("${greenGate.outputReceiptId}.receipt.json") },
        layout.projectDirectory.file(task.completionReceipt.outputPath),
        layout.projectDirectory.file(task.outputs.single().path),
        sha256(canonicalJson(task.inputs)).value,
        sha256(canonicalJson(mapOf(
            "receiptId" to task.completionReceipt.receiptId,
            "requiredGateIds" to task.completionReceipt.requiredGateIds.sorted(),
            "requiredDependencyReceiptIds" to task.completionReceipt.dependencyReceiptIds.sorted(),
        ))).value,
    )
}
