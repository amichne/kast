package support.delivery

@JvmInline
internal value class GradleGateTaskName internal constructor(val value: String)

internal enum class DeliveryGateGraphFailure {
    UNSUPPORTED_GATE_KIND,
    DUPLICATE_GATE_ID,
    DUPLICATE_RECEIPT_OUTPUT,
    MISSING_TASK_GATE,
    GATE_CONTRACT_MISMATCH,
    DEPENDENCY_RECEIPT_MISMATCH,
    REGISTERED_TASK_MISSING,
    UNREPRESENTED_REGISTERED_TASK,
}

@ConsistentCopyVisibility
internal data class AdmittedDeliveryGateGraph internal constructor(
    val gates: List<GateNode>,
    val registeredTasks: Set<GradleGateTaskName>,
)

internal sealed interface DeliveryGateGraphAdmission {
    data class Admitted(val graph: AdmittedDeliveryGateGraph) : DeliveryGateGraphAdmission
    data class Rejected(val failure: DeliveryGateGraphFailure) : DeliveryGateGraphAdmission
}

internal sealed interface GradleGateTaskNameRefinement {
    data class Refined(val name: GradleGateTaskName) : GradleGateTaskNameRefinement
    data class Rejected(val failure: DeliveryGateGraphFailure) : GradleGateTaskNameRefinement
}

/**
 * Proof transition: `GateNode -> GradleGateTaskName`.
 *
 * Establishes the sole program-derived Gradle receipt-task identity for RED, GREEN, or task
 * completion. Unsupported kinds return [DeliveryGateGraphFailure.UNSUPPORTED_GATE_KIND]. Raw task
 * name extraction is permitted only by convention registration and Gradle task inputs.
 */
internal fun refineGradleGateTaskName(gate: GateNode): GradleGateTaskNameRefinement {
    val stem = gate.taskId.value.replace("-", "")
    val name = when (gate.kind) {
        GateKind.RED -> "record${stem}RedReceipt"
        GateKind.GREEN -> "record${stem}GreenReceipt"
        GateKind.TASK_COMPLETION -> "derive${stem}Completion"
        else -> return GradleGateTaskNameRefinement.Rejected(
            DeliveryGateGraphFailure.UNSUPPORTED_GATE_KIND,
        )
    }
    return GradleGateTaskNameRefinement.Refined(GradleGateTaskName(name))
}

/**
 * Proof transition: `DeliveryProgram` plus registered Gradle task names ->
 * `AdmittedDeliveryGateGraph`.
 *
 * Establishes one exact RED, GREEN, and completion gate per task, exact predecessor-receipt inputs,
 * unique output receipts, and a bijection with registered Gradle gate tasks. Expected failure is
 * the finite [DeliveryGateGraphFailure] set. Raw registered names enter only from the convention
 * plugin after `tasks.named` has proven each task exists.
 */
internal fun admitDeliveryGateGraph(
    program: DeliveryProgram,
    registeredTaskNames: Set<String>,
): DeliveryGateGraphAdmission {
    if (program.gates.map { it.id }.toSet().size != program.gates.size) {
        return gateGraphRejected(DeliveryGateGraphFailure.DUPLICATE_GATE_ID)
    }
    if (program.gates.map { it.outputReceiptId }.toSet().size != program.gates.size) {
        return gateGraphRejected(DeliveryGateGraphFailure.DUPLICATE_RECEIPT_OUTPUT)
    }
    val tasks = program.tasks.associateBy { it.id }
    for (task in program.tasks) {
        val taskGates = program.gates.filter { it.taskId == task.id }
        if (taskGates.size != 3) {
            return gateGraphRejected(DeliveryGateGraphFailure.MISSING_TASK_GATE)
        }
        val gates = taskGates.associateBy { it.kind }
        if (gates.keys != setOf(GateKind.RED, GateKind.GREEN, GateKind.TASK_COMPLETION)) {
            return gateGraphRejected(DeliveryGateGraphFailure.MISSING_TASK_GATE)
        }
        val dependencyReceipts = task.dependencies.taskIds.mapTo(mutableSetOf()) {
            "${it.value}-COMPLETE"
        }
        val red = gates.getValue(GateKind.RED)
        val green = gates.getValue(GateKind.GREEN)
        val completion = gates.getValue(GateKind.TASK_COMPLETION)
        if (red.id != task.red.gateId || red.command != task.red.command ||
            red.outputReceiptId != "${task.id.value}-RED-RECEIPT" ||
            green.id != task.green.gateId || green.command != task.green.command ||
            green.outputReceiptId != "${task.id.value}-GREEN-RECEIPT" ||
            completion.id != "${task.id.value}-COMPLETE-GATE" ||
            completion.command != "./gradlew derive${task.id.value.replace("-", "")}Completion" ||
            completion.outputReceiptId != task.completionReceipt.receiptId ||
            task.completionReceipt.requiredGateIds != setOf(red.id, green.id) ||
            task.completionReceipt.dependencyReceiptIds != dependencyReceipts
        ) return gateGraphRejected(DeliveryGateGraphFailure.GATE_CONTRACT_MISMATCH)
        if (red.dependencyReceiptIds != dependencyReceipts ||
            green.dependencyReceiptIds != dependencyReceipts + red.outputReceiptId ||
            completion.dependencyReceiptIds != dependencyReceipts + setOf(
                red.outputReceiptId,
                green.outputReceiptId,
            )
        ) return gateGraphRejected(DeliveryGateGraphFailure.DEPENDENCY_RECEIPT_MISMATCH)
    }
    if (program.gates.any { it.taskId !in tasks }) {
        return gateGraphRejected(DeliveryGateGraphFailure.GATE_CONTRACT_MISMATCH)
    }
    val expectedTasks = mutableSetOf<GradleGateTaskName>()
    for (gate in program.gates) {
        when (val refined = refineGradleGateTaskName(gate)) {
            is GradleGateTaskNameRefinement.Refined -> expectedTasks += refined.name
            is GradleGateTaskNameRefinement.Rejected -> return gateGraphRejected(refined.failure)
        }
    }
    val registered = registeredTaskNames.mapTo(mutableSetOf(), ::GradleGateTaskName)
    if (!registered.containsAll(expectedTasks)) {
        return gateGraphRejected(DeliveryGateGraphFailure.REGISTERED_TASK_MISSING)
    }
    if (!expectedTasks.containsAll(registered)) {
        return gateGraphRejected(DeliveryGateGraphFailure.UNREPRESENTED_REGISTERED_TASK)
    }
    return DeliveryGateGraphAdmission.Admitted(
        AdmittedDeliveryGateGraph(program.gates.sortedBy { it.id }, registered),
    )
}

private fun gateGraphRejected(failure: DeliveryGateGraphFailure) =
    DeliveryGateGraphAdmission.Rejected(failure)
