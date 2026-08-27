package support.delivery

/**
 * Proof transition: legacy RED/GREEN/completion authoring -> one closed task proof protocol.
 *
 * KVP-001 through KVP-024 preserve their already-admitted three-receipt contract. KVP-025 and
 * later refine the negative and legal checks into named cases owned by one public Gradle command
 * and one [TaskProofReceiptContract]. Raw command text exits only at Gradle registration and JSON
 * projection boundaries.
 */
sealed interface TaskProofProtocol {
    val gates: List<GateNode>

    @ConsistentCopyVisibility
    data class Legacy internal constructor(
        val red: ProofCommand,
        val green: ProofCommand,
        val completion: CompletionReceiptContract,
        override val gates: List<GateNode>,
    ) : TaskProofProtocol

    @ConsistentCopyVisibility
    data class Atomic internal constructor(
        val command: TaskProofCommand,
        val receipt: TaskProofReceiptContract,
    ) : TaskProofProtocol {
        override val gates: List<GateNode> = listOf(command.gate)
    }
}

@ConsistentCopyVisibility
data class TaskProofCommand internal constructor(
    val command: String,
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val gate: GateNode,
)

@ConsistentCopyVisibility
data class TaskProofReceiptContract internal constructor(
    val receiptId: ReceiptId,
    val dependencies: Set<ReceiptId>,
    val outputPath: String,
    val headPolicy: TaskProofHeadPolicy,
) {
    val requiresExactHead: Boolean get() = headPolicy == TaskProofHeadPolicy.EXACT_HEAD
}

internal fun deriveTaskProofProtocol(task: TaskNode): TaskProofProtocol =
    if (task.id < ATOMIC_TASK_PROOF_FRONTIER && task.id !in LATE_ATOMIC_TASKS) {
        TaskProofProtocol.Legacy(
            task.red,
            task.green,
            task.completionReceipt,
            legacyGates(task),
        )
    } else {
        val receipt = TaskProofReceiptContract(
            task.completionReceipt.identity,
            task.completionReceipt.dependencyReceipts,
            task.completionReceipt.outputPath,
            if (task.id in EXACT_HEAD_TASKS) {
                TaskProofHeadPolicy.EXACT_HEAD
            } else {
                TaskProofHeadPolicy.CONTENT_SCOPED
            },
        )
        val gate = GateNode(
            "${task.id.value}-PROOF",
            task.id,
            GateKind.TASK_PROOF,
            "./gradlew prove${task.id.value.replace("-", "")}",
            "Exercises ${task.red.gateId} misuse rejection and " +
                "${task.green.gateId} legal path before issuing one task receipt.",
            receipt.dependencies.mapTo(linkedSetOf()) { it.value },
            receipt.receiptId.value,
        )
        TaskProofProtocol.Atomic(
            TaskProofCommand(gate.command, task.red, task.green, gate),
            receipt,
        )
    }

private fun legacyGates(task: TaskNode): List<GateNode> {
    val predecessors = task.completionReceipt.dependencyReceiptIds
    val redReceipt = "${task.id.value}-RED-RECEIPT"
    val greenReceipt = "${task.id.value}-GREEN-RECEIPT"
    return listOf(
        GateNode(
            task.red.gateId,
            task.id,
            GateKind.RED,
            task.red.command,
            task.red.expectation,
            predecessors,
            redReceipt,
        ),
        GateNode(
            task.green.gateId,
            task.id,
            GateKind.GREEN,
            task.green.command,
            task.green.expectation,
            predecessors + redReceipt,
            greenReceipt,
        ),
        GateNode(
            "${task.id.value}-COMPLETE-GATE",
            task.id,
            GateKind.TASK_COMPLETION,
            "./gradlew derive${task.id.value.replace("-", "")}Completion",
            "Derives task completion from admitted RED, GREEN, and predecessor receipts.",
            predecessors + redReceipt + greenReceipt,
            task.completionReceipt.receiptId,
        ),
    )
}

private val ATOMIC_TASK_PROOF_FRONTIER = TaskId("KVP-025")
private val LATE_ATOMIC_TASKS = setOf(TaskId("KVP-011"))
private val EXACT_HEAD_TASKS = setOf(
    TaskId("KVP-031"),
    TaskId("KVP-034"),
    TaskId("KVP-036"),
    TaskId("KVP-043"),
)
