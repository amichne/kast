package support.delivery

internal enum class DeliveryStateFailure {
    NON_COMPLETION_RECEIPT,
    RECEIPT_TASK_ID_MISMATCH,
    UNKNOWN_TASK_RECEIPT,
}

internal enum class DeliveryTaskInvalidation {
    DUPLICATE_COMPLETION_RECEIPT,
    STALE_EXACT_HEAD,
}

internal class AdmittedCompletionReceipt private constructor(
    val receipt: AdmittedProofReceipt,
) {
    val taskId: TaskId get() = receipt.taskId
    val exactHead: AuthorityGitRevision get() = receipt.exactHead

    internal companion object {
        /**
         * Proof transition: `AdmittedProofReceipt -> CompletionReceiptRefinement`.
         * Establishes matching task, receipt, and completion-gate identities. Expected mismatch is
         * finite [DeliveryStateFailure]; raw receipt identities do not pass this boundary.
         */
        fun refine(receipt: AdmittedProofReceipt): CompletionReceiptRefinement {
            val task = receipt.taskId.value
            if (receipt.receiptId.value != "$task-COMPLETE") {
                return CompletionReceiptRefinement.Rejected(
                    DeliveryStateFailure.NON_COMPLETION_RECEIPT,
                )
            }
            if (receipt.gateId.value != "$task-COMPLETE-GATE") {
                return CompletionReceiptRefinement.Rejected(
                    DeliveryStateFailure.RECEIPT_TASK_ID_MISMATCH,
                )
            }
            return CompletionReceiptRefinement.Complete(AdmittedCompletionReceipt(receipt))
        }
    }
}

internal sealed interface CompletionReceiptRefinement {
    data class Complete(val receipt: AdmittedCompletionReceipt) : CompletionReceiptRefinement
    data class Rejected(val failure: DeliveryStateFailure) : CompletionReceiptRefinement
}

internal sealed interface DerivedTaskState {
    val taskId: TaskId

    data class Blocked(
        override val taskId: TaskId,
        val missingReceipts: Set<ReceiptId>,
    ) : DerivedTaskState {
        init { require(missingReceipts.isNotEmpty()) }
    }

    data class Ready(override val taskId: TaskId) : DerivedTaskState

    data class Invalid(
        override val taskId: TaskId,
        val invalidation: DeliveryTaskInvalidation,
    ) : DerivedTaskState

    data class Proven(
        override val taskId: TaskId,
        val completionReceipt: AdmittedCompletionReceipt,
    ) : DerivedTaskState
}

internal sealed interface DerivedRequirementState {
    val requirementId: RequirementId

    data class Pending(
        override val requirementId: RequirementId,
        val missingTaskIds: Set<TaskId>,
    ) : DerivedRequirementState {
        init { require(missingTaskIds.isNotEmpty()) }
    }

    data class Passed(
        override val requirementId: RequirementId,
        val completionReceipts: List<AdmittedCompletionReceipt>,
    ) : DerivedRequirementState {
        init { require(completionReceipts.isNotEmpty()) }
    }
}

internal class DerivedTerminalCompletion private constructor(
    val exactHead: AuthorityGitRevision,
    val terminalReceipt: AdmittedCompletionReceipt,
) {
    internal companion object {
        fun derive(
            exactHead: AuthorityGitRevision,
            terminalTask: TaskId,
            taskStates: Map<TaskId, DerivedTaskState>,
            requirementStates: Map<RequirementId, DerivedRequirementState>,
        ): DerivedTerminalState {
            val pendingTasks = taskStates.filterValues { it !is DerivedTaskState.Proven }.keys
            val pendingRequirements = requirementStates.filterValues {
                it !is DerivedRequirementState.Passed
            }.keys
            if (pendingTasks.isNotEmpty() || pendingRequirements.isNotEmpty()) {
                return DerivedTerminalState.Pending(pendingTasks, pendingRequirements)
            }
            val terminal = when (val state = taskStates.getValue(terminalTask)) {
                is DerivedTaskState.Proven -> state
                else -> return DerivedTerminalState.Pending(setOf(terminalTask), emptySet())
            }
            return DerivedTerminalState.Proven(
                DerivedTerminalCompletion(exactHead, terminal.completionReceipt),
            )
        }
    }
}

internal sealed interface DerivedTerminalState {
    data class Pending(
        val pendingTaskIds: Set<TaskId>,
        val pendingRequirementIds: Set<RequirementId>,
    ) : DerivedTerminalState

    data class Proven(val completion: DerivedTerminalCompletion) : DerivedTerminalState
}

@ConsistentCopyVisibility
internal data class DerivedProgramState internal constructor(
    val exactHead: AuthorityGitRevision,
    val taskStates: Map<TaskId, DerivedTaskState>,
    val requirementStates: Map<RequirementId, DerivedRequirementState>,
    val criticalPath: List<TaskId>,
    val terminal: DerivedTerminalState,
)

internal sealed interface DerivedProgramStateResult {
    data class Complete(val state: DerivedProgramState) : DerivedProgramStateResult
    data class Rejected(val failure: DeliveryStateFailure) : DerivedProgramStateResult
}

/**
 * Proof transition: validated program, exact head, and admitted receipts ->
 * `DerivedProgramStateResult`.
 *
 * Establishes closed task and requirement progression, a deterministic critical path, and terminal
 * completion only for one complete exact-head closure. Expected identity failure is finite
 * [DeliveryStateFailure]. Receipt bytes and filenames remain outside this pure boundary.
 */
internal fun deriveProgramState(
    validated: ValidatedProgram,
    exactHead: AuthorityGitRevision,
    admittedReceipts: List<AdmittedProofReceipt>,
): DerivedProgramStateResult {
    val completions = mutableListOf<AdmittedCompletionReceipt>()
    for (receipt in admittedReceipts) {
        when (val result = AdmittedCompletionReceipt.refine(receipt)) {
            is CompletionReceiptRefinement.Complete -> completions += result.receipt
            is CompletionReceiptRefinement.Rejected -> {
                return DerivedProgramStateResult.Rejected(result.failure)
            }
        }
    }
    val program = validated.program
    val knownTasks = program.tasks.mapTo(mutableSetOf()) { it.id }
    if (completions.any { it.taskId !in knownTasks }) {
        return DerivedProgramStateResult.Rejected(DeliveryStateFailure.UNKNOWN_TASK_RECEIPT)
    }
    val receiptsByTask = completions.groupBy { it.taskId }
    val taskStates = linkedMapOf<TaskId, DerivedTaskState>()
    for (taskId in validated.order) {
        val task = program.tasks.single { it.id == taskId }
        val receipts = receiptsByTask[taskId].orEmpty()
        val state = when {
            receipts.size > 1 -> DerivedTaskState.Invalid(
                taskId,
                DeliveryTaskInvalidation.DUPLICATE_COMPLETION_RECEIPT,
            )
            receipts.size == 1 && receipts.single().exactHead != exactHead ->
                DerivedTaskState.Invalid(taskId, DeliveryTaskInvalidation.STALE_EXACT_HEAD)
            receipts.size == 1 -> DerivedTaskState.Proven(taskId, receipts.single())
            dependenciesSatisfied(task.dependencies, taskStates) -> DerivedTaskState.Ready(taskId)
            else -> DerivedTaskState.Blocked(
                taskId,
                task.dependencies.taskIds.filterTo(mutableSetOf()) {
                    taskStates[it] !is DerivedTaskState.Proven
                }.mapTo(mutableSetOf()) { ReceiptId("${it.value}-COMPLETE") },
            )
        }
        taskStates[taskId] = state
    }
    val requirementStates = program.requirements.associate { requirement ->
        val provingTasks = program.tasks.filter { requirement.id in it.provesRequirements }
        val provingStates = provingTasks.associate { it.id to taskStates.getValue(it.id) }
        val proven = provingStates.values.filterIsInstance<DerivedTaskState.Proven>()
            .map { it.completionReceipt }
        val state = if (proven.size == provingTasks.size) {
            DerivedRequirementState.Passed(requirement.id, proven)
        } else {
            DerivedRequirementState.Pending(
                requirement.id,
                provingStates.filterValues { it !is DerivedTaskState.Proven }.keys,
            )
        }
        requirement.id to state
    }
    val terminal = DerivedTerminalCompletion.derive(
        exactHead,
        program.terminalTask,
        taskStates,
        requirementStates,
    )
    return DerivedProgramStateResult.Complete(
        DerivedProgramState(
            exactHead,
            taskStates,
            requirementStates,
            deriveCriticalPath(validated),
            terminal,
        ),
    )
}

private fun dependenciesSatisfied(
    dependency: DependencyExpression,
    states: Map<TaskId, DerivedTaskState>,
): Boolean = when (dependency.kind) {
    EdgeKind.REQUIRES_ONE -> dependency.taskIds.any { states[it] is DerivedTaskState.Proven }
    else -> dependency.taskIds.all { states[it] is DerivedTaskState.Proven }
}

private fun deriveCriticalPath(validated: ValidatedProgram): List<TaskId> {
    val byId = validated.program.tasks.associateBy { it.id }
    val reversed = mutableListOf<TaskId>()
    var current = validated.program.terminalTask
    while (true) {
        reversed += current
        val dependencies = byId.getValue(current).dependencies.taskIds
        if (dependencies.isEmpty()) break
        current = dependencies.maxWith(
            compareBy<TaskId> { validated.waves.getValue(it) }.thenBy { it.value },
        )
    }
    return reversed.asReversed()
}
