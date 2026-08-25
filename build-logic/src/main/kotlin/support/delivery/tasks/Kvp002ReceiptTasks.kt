package support.delivery

import java.nio.file.Path
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

internal enum class Kvp002TypeFamily(val projectionName: String) {
    IDENTITY("identity"),
    GENERATION("generation"),
    DEPENDENCY("dependency"),
    AUTHORITY("authority"),
    EFFECT("effect"),
    COST("cost"),
    EVIDENCE("evidence"),
    GATE("gate"),
    RECEIPT("receipt"),
    PROGRESSION("progression"),
    CLOSED_OUTCOME("closedOutcome"),
}

internal enum class Kvp002RejectedCase {
    INVALID_TASK_ID,
    INVALID_GENERATION,
    INVALID_AUTHORITY_ID,
    INVALID_EFFECT_ID,
    INVALID_COST_ID,
    INVALID_GATE_ID,
    INVALID_RECEIPT_ID,
    EMPTY_EVIDENCE,
    EMPTY_LIMITATIONS,
}

internal enum class Kvp002TypeProofFailure {
    REFINEMENT_MISMATCH,
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    OUTCOME_MISMATCH,
    REJECTED_CASES_MISMATCH,
    TYPE_FAMILIES_MISMATCH,
}

@ConsistentCopyVisibility
internal data class Kvp002TypeProof internal constructor(
    val taskId: TaskId,
    val rejectedCases: Set<Kvp002RejectedCase>,
    val typeFamilies: Set<Kvp002TypeFamily>,
)

internal sealed interface Kvp002TypeProofResult {
    data class Complete(val proof: Kvp002TypeProof) : Kvp002TypeProofResult
    data class Rejected(val failure: Kvp002TypeProofFailure) : Kvp002TypeProofResult
}

@Serializable
private data class Kvp002TypeProofJsonDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: String,
    val rejectedCases: List<String>,
    val provedTypeFamilies: List<String>,
)

private val kvp002ProofJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

private val expectedKvp002RejectedCases = Kvp002RejectedCase.entries.toSet()
private val expectedKvp002TypeFamilies = Kvp002TypeFamily.entries.toSet()

/**
 * Proof transition: current delivery-model operations -> `Kvp002TypeProofResult`.
 * Establishes every KVP-002 invalid refinement and distinct type family. Expected mismatch is
 * finite [Kvp002TypeProofFailure]; raw values leave only through the JSON/Gradle boundary below.
 */
internal fun deriveKvp002TypeProof(): Kvp002TypeProofResult {
    val rejected = listOf(
        refineTaskId("task-2") to DeliveryModelFailure.INVALID_TASK_ID,
        refineDeliveryGeneration("main") to DeliveryModelFailure.INVALID_GENERATION,
        refineAuthorityId("delivery authority") to DeliveryModelFailure.INVALID_AUTHORITY_ID,
        refineEffectId("filesystem-read") to DeliveryModelFailure.INVALID_EFFECT_ID,
        refineCostId("build policy") to DeliveryModelFailure.INVALID_COST_ID,
        refineGateId("KVP-002") to DeliveryModelFailure.INVALID_GATE_ID,
        refineReceiptId("KVP-002-RECEIPT") to DeliveryModelFailure.INVALID_RECEIPT_ID,
        EvidenceSet.refine(emptyList()) to DeliveryModelFailure.EMPTY_EVIDENCE,
        NonEmptyLimitations.refine(emptyList()) to DeliveryModelFailure.EMPTY_LIMITATIONS,
    )
    if (rejected.any { (result, failure) ->
            result !is DeliveryRefinement.Rejected || result.failure != failure
        }
    ) return Kvp002TypeProofResult.Rejected(Kvp002TypeProofFailure.REFINEMENT_MISMATCH)
    val valid = listOf(
        refineTaskId("KVP-002"),
        refineDeliveryGeneration("78262728313c90bb847e73425dc1a76d704397db"),
        refineAuthorityId("DELIVERY_PROGRAM"),
        refineEffectId("BUILD_POLICY_WRITE"),
        refineCostId("BUILD_POLICY"),
        refineGateId("KVP-002-GREEN"),
        refineReceiptId("KVP-002-COMPLETE"),
        EvidenceSet.refine(
            listOf(Evidence(EvidenceKind.PROOF_ARTIFACT, sha256("KVP-002-types"))),
        ),
        NonEmptyLimitations.refine(listOf("bounded qualification")),
    )
    if (valid.any { it !is DeliveryRefinement.Complete }) {
        return Kvp002TypeProofResult.Rejected(Kvp002TypeProofFailure.REFINEMENT_MISMATCH)
    }
    return Kvp002TypeProofResult.Complete(
        Kvp002TypeProof(TaskId("KVP-002"), expectedKvp002RejectedCases, expectedKvp002TypeFamilies),
    )
}

/**
 * Proof transition: `Kvp002TypeProof -> String`.
 * Preserves the exact proof sets in generated JSON. No expected failure exists after refinement;
 * raw JSON is emitted only at the Gradle report boundary.
 */
internal fun encodeKvp002TypeProof(proof: Kvp002TypeProof): String =
    kvp002ProofJson.encodeToString(
        Kvp002TypeProofJsonDocument.serializer(),
        Kvp002TypeProofJsonDocument(
            schemaVersion = 1,
            taskId = proof.taskId.value,
            outcome = "COMPLETE",
            rejectedCases = proof.rejectedCases.map { it.name }.sorted(),
            provedTypeFamilies = proof.typeFamilies.map { it.projectionName }.sorted(),
        ),
    ) + "\n"

/**
 * Proof transition: report JSON `String -> Kvp002TypeProofResult`.
 * Establishes schema, task, outcome, negative cases, and type-family equality. Expected malformed
 * or mismatched evidence is finite [Kvp002TypeProofFailure]; raw JSON remains at this boundary.
 */
internal fun decodeKvp002TypeProof(raw: String): Kvp002TypeProofResult {
    val document = try {
        kvp002ProofJson.decodeFromString(Kvp002TypeProofJsonDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp002TypeProofResult.Rejected(Kvp002TypeProofFailure.MALFORMED_DOCUMENT)
    }
    val failure = when {
        document.schemaVersion != 1 -> Kvp002TypeProofFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-002" -> Kvp002TypeProofFailure.TASK_ID_MISMATCH
        document.outcome != "COMPLETE" -> Kvp002TypeProofFailure.OUTCOME_MISMATCH
        document.rejectedCases != expectedKvp002RejectedCases.map { it.name }.sorted() ->
            Kvp002TypeProofFailure.REJECTED_CASES_MISMATCH
        document.provedTypeFamilies != expectedKvp002TypeFamilies.map { it.projectionName }.sorted() ->
            Kvp002TypeProofFailure.TYPE_FAMILIES_MISMATCH
        else -> null
    }
    return if (failure == null) deriveKvp002TypeProof() else Kvp002TypeProofResult.Rejected(failure)
}

abstract class Kvp002ReceiptTaskBase : Kvp001ReceiptTaskBase() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input abstract val candidateTaskId: Property<String>
    @get:Input abstract val candidateRedGateId: Property<String>
    @get:Input abstract val candidateGreenGateId: Property<String>
    @get:Input abstract val candidateCompletionGateId: Property<String>
    @get:Input abstract val candidateRedReceiptId: Property<String>
    @get:Input abstract val candidateGreenReceiptId: Property<String>
    @get:Input abstract val candidateCompletionReceiptId: Property<String>
    @get:Input abstract val candidateRedCommand: Property<String>
    @get:Input abstract val candidateGreenCommand: Property<String>
    @get:Input abstract val candidateCompletionCommand: Property<String>
    @get:Input abstract val candidateTaskInputDigest: Property<String>
    @get:Input abstract val candidateCompletionInputDigest: Property<String>
    @get:Input abstract val proofReportPath: Property<String>

    @get:InputFile abstract val authorityRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val authorityGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val authorityCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-002 command plus fixed test filter -> successful gate process.
     * Establishes exact command equality and zero exit; mismatch is [ProofReceiptFailure]. Raw
     * process arguments are exposed only to Gradle's process boundary.
     */
    internal fun runDeclaredGate(command: String, testFilter: String) {
        val expected = "./gradlew :build-logic:test --tests \"$testFilter\""
        if (command != expected) rejectReceipt("KVP-002 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", ":build-logic:test", "--tests", testFilter)
        }
    }

    /**
     * Proof transition: configured task inputs plus `AuthorityGitRevision` ->
     * `Kvp002ReceiptContexts`. Establishes the complete admitted KVP-001 dependency; receipt
     * failures remain closed until rendered at this Gradle boundary. Raw properties stay here.
     */
    internal fun contexts(exactHead: AuthorityGitRevision): Kvp002ReceiptContexts {
        val authority = context(exactHead)
        val red = authority.admit(authorityRedReceiptFile.get().asFile.toPath(), authority.redExpectation())
        val green = authority.admit(
            authorityGreenReceiptFile.get().asFile.toPath(),
            authority.greenExpectation(red),
        )
        val completion = authority.admit(
            authorityCompletionReceiptFile.get().asFile.toPath(),
            authority.completionExpectation(red, green),
        )
        return Kvp002ReceiptContexts(
            authority,
            completion,
            candidateTaskId.get(),
            candidateRedGateId.get(),
            candidateGreenGateId.get(),
            candidateCompletionGateId.get(),
            candidateRedReceiptId.get(),
            candidateGreenReceiptId.get(),
            candidateCompletionReceiptId.get(),
            candidateRedCommand.get(),
            candidateGreenCommand.get(),
            candidateCompletionCommand.get(),
            candidateTaskInputDigest.get(),
            candidateCompletionInputDigest.get(),
            proofReportPath.get(),
        )
    }
}

internal data class Kvp002ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val authorityCompletion: AdmittedProofReceipt,
    val taskId: String,
    val redGateId: String,
    val greenGateId: String,
    val completionGateId: String,
    val redReceiptId: String,
    val greenReceiptId: String,
    val completionReceiptId: String,
    val redCommand: String,
    val greenCommand: String,
    val completionCommand: String,
    val taskInputDigest: String,
    val completionInputDigest: String,
    val proofReportPath: String,
) {
    fun proof(): Kvp002TypeProof = when (val result = deriveKvp002TypeProof()) {
        is Kvp002TypeProofResult.Complete -> result.proof
        is Kvp002TypeProofResult.Rejected -> rejectReceipt(
            "KVP-002 type proof", ProofReceiptFailure.MALFORMED_OBSERVATION, result.failure.name,
        )
    }

    fun reportProof(): Kvp002TypeProof = when (
        val result = decodeKvp002TypeProof(boundary.readText(proofReportPath))
    ) {
        is Kvp002TypeProofResult.Complete -> result.proof
        is Kvp002TypeProofResult.Rejected -> rejectReceipt(
            "KVP-002 type report", ProofReceiptFailure.MALFORMED_OBSERVATION, result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp002TypeProof) = boundary.expectation(
        redReceiptId, redGateId, redCommand, taskInputDigest,
        mapOf(authorityCompletion.receiptId.value to authorityCompletion.digest.value),
        mapOf(
            "outcome" to "COMPLETE",
            "rejectedCases" to proof.rejectedCases.map { it.name }.sorted().joinToString(","),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp002TypeProof) = boundary.expectation(
        greenReceiptId, greenGateId, greenCommand, taskInputDigest,
        mapOf(
            authorityCompletion.receiptId.value to authorityCompletion.digest.value,
            red.receiptId.value to red.digest.value,
        ),
        mapOf(
            "outcome" to "COMPLETE",
            "provedTypeFamilies" to proof.typeFamilies.map { it.projectionName }.sorted().joinToString(","),
        ),
        boundary.artifactDigests(listOf(proofReportPath)),
        taskId,
    )

    fun completionExpectation(red: AdmittedProofReceipt, green: AdmittedProofReceipt) =
        boundary.expectation(
            completionReceiptId, completionGateId, completionCommand, completionInputDigest,
            mapOf(
                authorityCompletion.receiptId.value to authorityCompletion.digest.value,
                red.receiptId.value to red.digest.value,
                green.receiptId.value to green.digest.value,
            ),
            mapOf("admittedGateReceiptCount" to "2", "outcome" to "COMPLETE"),
            emptyMap(),
            taskId,
        )
}

@UntrackedTask(because = "Executes and binds the exact KVP-002 RED gate")
abstract class RecordKvp002RedReceiptTask : Kvp002ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val head = observeExactHead(repositoryRoot())
        runDeclaredGate(candidateRedCommand.get(), "*DeliveryProgramModelNegativeTest")
        revalidateExactHead(repositoryRoot(), head)
        val contexts = contexts(head)
        issueReceiptAtBoundary(repositoryRoot(), head, contexts.redExpectation(contexts.proof()), receiptFile.get().asFile.toPath())
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-002 GREEN gate")
abstract class RecordKvp002GreenReceiptTask : Kvp002ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runDeclaredGate(candidateGreenCommand.get(), "*DeliveryProgramModelTest")
        revalidateExactHead(root, head)
        val contexts = contexts(head)
        writeTextAtomically(proofReportFile.get().asFile.toPath(), encodeKvp002TypeProof(contexts.proof()))
        revalidateExactHead(root, head)
        val red = contexts.boundary.admit(redReceiptFile.get().asFile.toPath(), contexts.redExpectation(contexts.proof()))
        issueReceiptAtBoundary(root, head, contexts.greenExpectation(red, contexts.reportProof()), receiptFile.get().asFile.toPath())
    }
}

@UntrackedTask(because = "Derives KVP-002 completion from admitted dependency and gate receipts")
abstract class DeriveKvp002CompletionReceiptTask : Kvp002ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = contexts(head)
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(redReceiptFile.get().asFile.toPath(), contexts.redExpectation(proof))
        val green = contexts.boundary.admit(greenReceiptFile.get().asFile.toPath(), contexts.greenExpectation(red, proof))
        issueReceiptAtBoundary(root, head, contexts.completionExpectation(red, green), receiptFile.get().asFile.toPath())
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-002 receipt closure at live Git HEAD")
abstract class VerifyKvp002CompletionReceiptTask : Kvp002ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = contexts(head)
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(redReceiptFile.get().asFile.toPath(), contexts.redExpectation(proof))
        val green = contexts.boundary.admit(greenReceiptFile.get().asFile.toPath(), contexts.greenExpectation(red, proof))
        val completion = contexts.boundary.admit(completionReceiptFile.get().asFile.toPath(), contexts.completionExpectation(red, green))
        revalidateExactHead(root, head)
        logger.lifecycle("KVP-002-COMPLETE admitted at {} with receipt digest {}", completion.exactHead.value, completion.digest.value)
    }
}
