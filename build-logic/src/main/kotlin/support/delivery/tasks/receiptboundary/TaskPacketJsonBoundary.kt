package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class TaskPacketJsonDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskDefinitionDigest: String,
    val task: TaskPacketTaskDocument,
    val proof: TaskPacketProofDocument,
)

@Serializable
private data class TaskPacketTaskDocument(
    val id: String,
    val title: String,
    val goal: String,
    val milestone: String,
    val dependencyTaskIds: List<String>,
    val allowedReads: List<String>,
    val allowedWrites: List<String>,
    val inputs: List<TaskPacketInputDocument>,
    val outputs: List<TaskPacketOutputDocument>,
    val publicInterface: String,
    val internalImplementation: String,
    val effectClassifications: List<String>,
    val costClassifications: List<String>,
    val forbiddenWork: List<String>,
    val reviewBoundary: String,
    val requirementIds: List<String>,
    val authorityIds: List<String>,
)

@Serializable
private data class TaskPacketInputDocument(val id: String, val kind: String)

@Serializable
private data class TaskPacketOutputDocument(
    val id: String,
    val kind: String,
    val path: String,
    val description: String,
)

@Serializable
private data class TaskPacketProofDocument(
    val command: String,
    val gateId: String,
    val misuse: TaskPacketCaseDocument,
    val legalPath: TaskPacketCaseDocument,
    val receipt: TaskPacketReceiptDocument,
)

@Serializable
private data class TaskPacketCaseDocument(
    val id: String,
    val name: String,
    val command: String,
    val expectation: String,
)

@Serializable
private data class TaskPacketReceiptDocument(
    val id: String,
    val dependencyReceiptIds: List<String>,
    val outputPath: String,
    val headPolicy: String,
)

internal enum class TaskPacketFileFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    PACKET_MISMATCH,
}

internal class AdmittedTaskPacketFile internal constructor(
    val packet: TaskPacket,
    val programVersion: TaskProofProgramVersion,
    val documentDigest: Sha256,
)

internal sealed interface TaskPacketFileAdmission {
    data class Complete(val admitted: AdmittedTaskPacketFile) : TaskPacketFileAdmission
    data class Rejected(val failure: TaskPacketFileFailure) : TaskPacketFileAdmission
}

private val taskPacketJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: `(TaskPacket, TaskProofProgramVersion) -> task-packet JSON`.
 *
 * Preserves every graph-owned task field, the canonical definition digest, and the sole atomic
 * proof/receipt contract in a generated closed document. Input shape was already established by
 * canonical program admission. Raw JSON exits only at the Gradle packet boundary.
 */
internal fun encodeTaskPacket(
    packet: TaskPacket,
    programVersion: TaskProofProgramVersion,
): String = taskPacketJson.encodeToString(
    TaskPacketJsonDocument.serializer(),
    packet.document(programVersion),
) + "\n"

/**
 * Proof transition: task-packet JSON plus canonical graph packet -> `TaskPacketFileAdmission`.
 *
 * Generated decoding and canonical byte equality establish that no task field was restated or
 * weakened outside the Kotlin graph. Expected malformed or mismatched evidence remains finite
 * [TaskPacketFileFailure]. Raw JSON is permitted only at this boundary.
 */
internal fun admitTaskPacket(
    raw: String,
    expected: TaskPacket,
    programVersion: TaskProofProgramVersion,
): TaskPacketFileAdmission {
    val document = try {
        taskPacketJson.decodeFromString(TaskPacketJsonDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return TaskPacketFileAdmission.Rejected(TaskPacketFileFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return TaskPacketFileAdmission.Rejected(TaskPacketFileFailure.MALFORMED_DOCUMENT)
    }
    val expectedDocument = expected.document(programVersion)
    if (document != expectedDocument) {
        return TaskPacketFileAdmission.Rejected(TaskPacketFileFailure.PACKET_MISMATCH)
    }
    if (raw != encodeTaskPacket(expected, programVersion)) {
        return TaskPacketFileAdmission.Rejected(TaskPacketFileFailure.NON_CANONICAL_DOCUMENT)
    }
    return TaskPacketFileAdmission.Complete(
        AdmittedTaskPacketFile(expected, programVersion, sha256(raw)),
    )
}

private fun TaskPacket.document(programVersion: TaskProofProgramVersion): TaskPacketJsonDocument {
    val proof = proofCommand
    return TaskPacketJsonDocument(
        schemaVersion = 1,
        programVersion = programVersion.value,
        taskDefinitionDigest = taskDefinitionDigest.value,
        task = TaskPacketTaskDocument(
            task.id.value,
            task.title,
            task.goal,
            task.milestone,
            task.dependencies.taskIds.map { it.value }.sorted(),
            task.allowedReads,
            task.allowedWrites,
            task.inputs.map { input ->
                TaskPacketInputDocument(input.getValue("id"), input.getValue("kind"))
            },
            task.outputs.map { output ->
                TaskPacketOutputDocument(
                    output.id,
                    output.kind,
                    output.path,
                    output.description,
                )
            },
            task.publicInterface,
            task.internalImplementation,
            task.effects.map { it.value }.sorted(),
            task.costs.sorted(),
            task.forbiddenWork,
            task.reviewBoundary,
            task.provesRequirements.map { it.value }.sorted(),
            task.authorities.map { it.value }.sorted(),
        ),
        proof = TaskPacketProofDocument(
            proof.command,
            proof.gate.id,
            proof.misuse.document(),
            proof.legalPath.document(),
            TaskPacketReceiptDocument(
                receipt.receiptId.value,
                receipt.dependencies.map { it.value }.sorted(),
                receipt.outputPath,
                receipt.headPolicy.name,
            ),
        ),
    )
}

private fun ProofCommand.document() = TaskPacketCaseDocument(
    gateId,
    namedCase,
    command,
    expectation,
)
