package support.delivery

data class ValidatedProgram(
    val program: DeliveryProgram,
    val order: List<TaskId>,
    val waves: Map<TaskId, Int>,
) {
    val gates: List<GateNode> = program.tasks.flatMap { it.proof.gates }

    /**
     * Proof transition: `(ValidatedProgram, TaskId) -> TaskPacketAdmission`.
     *
     * Establishes that the packet carries the exact admitted task definition, its canonical
     * definition digest, and its sole atomic proof command and receipt contract. Unknown or legacy
     * task identities return the finite [DeliveryModelFailure.UNKNOWN_TASK]. Raw packet fields are
     * extracted only by the Gradle task-packet boundary.
     */
    fun packet(taskId: TaskId): TaskPacketAdmission {
        val task = program.tasks.singleOrNull { it.id == taskId }
            ?: return TaskPacketAdmission.Rejected(DeliveryModelFailure.UNKNOWN_TASK)
        val proof = task.proof as? TaskProofProtocol.Atomic
            ?: return TaskPacketAdmission.Rejected(DeliveryModelFailure.UNKNOWN_TASK)
        return TaskPacketAdmission.Complete(
            TaskPacket(task, task.definitionDigest(), proof.command, proof.receipt),
        )
    }

    fun projection(): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "schemaVersion" to program.schemaVersion,
            "programId" to program.id.value,
            "name" to program.name,
            "targetHead" to program.targetHead,
            "requirementFingerprint" to program.requirementFingerprint.value,
            "sourceDigests" to program.sourceDigests.mapValues { it.value.value },
            "requirements" to program.requirements.sortedBy { it.id.value }.map {
                mapOf("id" to it.id.value, "statement" to it.statement)
            },
            "modules" to program.modules.sortedBy { it.id.value }.map(::moduleProjection),
            "authorities" to program.authorities.sortedBy { it.id.value }.map {
                mapOf("id" to it.id.value, "owner" to it.owner.value, "fact" to it.fact)
            },
            "effects" to program.effects.sortedBy { it.id.value }.map {
                mapOf(
                    "id" to it.id.value,
                    "owners" to it.owners.map { owner -> owner.value }.sorted(),
                    "purpose" to it.purpose,
                )
            },
            "tasks" to program.tasks.sortedBy { it.id }.map { task ->
                task.taskDefinitionProjection() + mapOf(
                    "taskDefinitionDigest" to task.definitionDigest().value,
                    "computedWave" to waves.getValue(task.id),
                )
            },
            "taskOrder" to order.map { it.value },
            "waveCount" to (waves.values.maxOrNull()!! + 1),
            "deliveryBatches" to program.deliveryBatches.sortedBy { it.id.value }.map { batch ->
                val selected = batch.tasks.mapTo(linkedSetOf()) { it.taskId }
                mapOf(
                    "id" to batch.id.value,
                    "taskOrder" to order.filter(selected::contains).map { it.value },
                    "externalDependencyTaskIds" to batch.tasks.flatMap { owned ->
                        program.tasks.single { it.id == owned.taskId }.dependencies.taskIds
                    }.filterNot(selected::contains).map { it.value }.distinct().sorted(),
                    "writeOwnership" to batch.tasks.sortedBy { it.taskId }.map { owned ->
                        mapOf(
                            "taskId" to owned.taskId.value,
                            "ownedWrites" to owned.ownedWrites,
                        )
                    },
                )
            },
            "specialEdges" to program.specialEdges.map {
                mapOf(
                    "kind" to it.kind.name.lowercase(),
                    "from" to it.from,
                    "target" to it.target,
                    "result" to it.result,
                )
            },
            "processGraph" to mapOf(
                "nodes" to program.processNodes.map {
                    mapOf("id" to it.id, "kind" to it.kind)
                },
                "transitions" to program.processTransitions.map {
                    mapOf(
                        "from" to it.from,
                        "to" to it.to,
                        "transition" to it.transition,
                        "failure" to it.failure,
                    )
                },
            ),
            "gateGraph" to gates.sortedBy { it.id }.map {
                mapOf(
                    "id" to it.id,
                    "taskId" to it.taskId.value,
                    "kind" to it.kind.name,
                    "command" to it.command,
                    "statement" to it.statement,
                    "dependsOnReceiptIds" to it.dependencyReceiptIds.sorted(),
                    "outputReceiptId" to it.outputReceiptId,
                )
            },
            "installedAcceptance" to mapOf(
                "ownerTask" to "KVP-034",
                "report" to "build/reports/ide-hosted/KVP-034-installed.json",
                "requiredMetrics" to program.installedMetrics.map {
                    mapOf("id" to it.id, "predicate" to it.predicate, "value" to it.value)
                },
            ),
            "terminal" to mapOf(
                "taskId" to program.terminalTask.value,
                "type" to "BestCaseVfsPassiveReusedIndex",
                "receiptPath" to
                    "build/reports/ide-hosted/best-case-vfs-passive-reused-index.receipt.json",
                "derivedOnly" to true,
            ),
        )
        val fingerprint = sha256(canonicalJson(base))
        return linkedMapOf<String, Any?>("programFingerprint" to fingerprint.value).apply {
            putAll(base)
        }
    }

    fun requirementTraceProjection(): Map<String, Any?> {
        val orderedTasks = program.tasks.sortedBy { it.id }
        val entries = program.requirements.sortedBy { it.id.value }.map { requirement ->
            val implementationTasks = orderedTasks.filter { requirement.id in it.provesRequirements }
            mapOf(
                "requirementId" to requirement.id.value,
                "statement" to requirement.statement,
                "implementationTaskIds" to implementationTasks.map { it.id.value },
                "enforcementGateIds" to implementationTasks.flatMap { task ->
                    task.proof.gates.map { it.id }
                },
                "finalRevalidationTaskId" to "KVP-042",
                "proofStateSource" to "ADMITTED_RECEIPTS",
            )
        }
        return mapOf(
            "schemaVersion" to 1,
            "programFingerprint" to projection().getValue("programFingerprint"),
            "entries" to entries,
        )
    }
}

@ConsistentCopyVisibility
data class TaskPacket internal constructor(
    val task: TaskNode,
    val taskDefinitionDigest: Sha256,
    val proofCommand: TaskProofCommand,
    val receipt: TaskProofReceiptContract,
)

sealed interface TaskPacketAdmission {
    data class Complete(val packet: TaskPacket) : TaskPacketAdmission
    data class Rejected(val failure: DeliveryModelFailure) : TaskPacketAdmission
}

/**
 * Proof transition: `TaskNode -> Sha256`.
 *
 * Establishes a digest over the complete canonical task packet definition, including proof cases,
 * command, dependency receipts, scope, classifications, and exact-head policy. Raw fields are
 * extracted only by deterministic projection and receipt issuance boundaries.
 */
fun TaskNode.definitionDigest(): Sha256 = sha256(canonicalJson(taskDefinitionProjection()))

internal fun TaskNode.taskDefinitionProjection(): Map<String, Any?> = linkedMapOf(
    "id" to id.value,
    "title" to title,
    "goal" to goal,
    "milestone" to milestone,
    "dependencyExpression" to mapOf(
        "kind" to "allOf",
        "taskIds" to dependencies.taskIds.map { it.value }.sorted(),
    ),
    "allowedReads" to allowedReads,
    "allowedWrites" to allowedWrites,
    "inputs" to inputs,
    "outputs" to outputs.map {
        mapOf("id" to it.id, "kind" to it.kind, "path" to it.path, "description" to it.description)
    },
    "publicInterface" to publicInterface,
    "internalImplementation" to internalImplementation,
    "effectClassification" to effects.map { it.value }.sorted(),
    "costClassification" to costs.sorted(),
    "forbiddenWork" to forbiddenWork,
    "proof" to proof.projection(),
    "reviewBoundary" to reviewBoundary,
    "provesRequirements" to provesRequirements.map { it.value }.sorted(),
    "authorities" to authorities.map { it.value }.sorted(),
)

private fun TaskProofProtocol.projection(): Map<String, Any?> = when (this) {
    is TaskProofProtocol.Legacy -> mapOf(
        "protocol" to "LEGACY_GATE_RECEIPTS",
        "red" to red.projection("expectedFailure"),
        "green" to green.projection("expectedProof"),
        "completionReceipt" to mapOf(
            "receiptId" to completion.receiptId,
            "requiredGateIds" to completion.requiredGateIds.sorted(),
            "requiredDependencyReceipts" to completion.dependencyReceiptIds.sorted(),
            "outputPath" to completion.outputPath,
        ),
    )
    is TaskProofProtocol.Atomic -> mapOf(
        "protocol" to "ATOMIC_TASK_PROOF",
        "command" to command.command,
        "gateId" to command.gate.id,
        "misuse" to command.misuse.projection("expectedRejection"),
        "legalPath" to command.legalPath.projection("expectedProof"),
        "receipt" to mapOf(
            "receiptId" to receipt.receiptId.value,
            "dependencyReceiptIds" to receipt.dependencies.map { it.value }.sorted(),
            "outputPath" to receipt.outputPath,
            "headPolicy" to receipt.headPolicy.name,
        ),
    )
}

private fun ProofCommand.projection(expectationName: String) = mapOf(
    "caseId" to gateId,
    "name" to namedCase,
    "command" to command,
    expectationName to expectation,
)

private fun moduleProjection(module: ModuleBoundary) = mapOf(
    "id" to module.id.value,
    "lifecycle" to module.lifecycle,
    "role" to module.role,
    "owns" to module.owns.sorted(),
    "dependencies" to module.dependencies.map { it.value }.sorted(),
    "authorities" to module.authorities.map { it.value }.sorted(),
    "effects" to module.effects.map { it.value }.sorted(),
)
