package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile

internal enum class Kvp004RejectedCase {
    INCOMPLETE_TASK_CONTRACT,
    DUPLICATE_AUTHORITY_OWNER,
    UNTRACED_REQUIREMENT,
    CYCLE,
}

internal enum class Kvp004ProgramProofFailure {
    ADMISSION_MISMATCH,
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    OUTCOME_MISMATCH,
    REJECTED_CASES_MISMATCH,
    PROGRAM_COUNTS_MISMATCH,
    TERMINAL_MISMATCH,
    ORDER_MISMATCH,
    WAVE_MISMATCH,
}

@ConsistentCopyVisibility
internal data class Kvp004ProgramProof internal constructor(
    val rejectedCases: Set<Kvp004RejectedCase>,
    val program: ValidatedProgram,
)

internal sealed interface Kvp004ProgramProofResult {
    data class Complete(val proof: Kvp004ProgramProof) : Kvp004ProgramProofResult
    data class Rejected(val failure: Kvp004ProgramProofFailure) : Kvp004ProgramProofResult
}

@Serializable
private data class Kvp004ProgramProofJsonDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: String,
    val rejectedCases: List<String>,
    val taskCount: Int,
    val requirementCount: Int,
    val moduleCount: Int,
    val authorityCount: Int,
    val effectCount: Int,
    val processNodeCount: Int,
    val processTransitionCount: Int,
    val gateCount: Int,
    val terminalTaskId: String,
    val taskOrder: List<String>,
    val waveCount: Int,
)

private val kvp004ProofJson = Json { ignoreUnknownKeys = false; prettyPrint = true }
private val expectedKvp004RejectedCases = Kvp004RejectedCase.entries.toSet()
private val expectedKvp004Counts = Kvp004ProgramCounts(43, 27, 21, 14, 22, 19, 17, 91)
private val expectedKvp004Order: List<TaskId> = buildList {
    addAll((1..10).map(::taskId))
    addAll((12..31).map(::taskId))
    add(taskId(11))
    addAll((32..43).map(::taskId))
}

private fun taskId(number: Int): TaskId = TaskId("KVP-${number.toString().padStart(3, '0')}")

private data class Kvp004ProgramCounts(
    val tasks: Int,
    val requirements: Int,
    val modules: Int,
    val authorities: Int,
    val effects: Int,
    val processNodes: Int,
    val processTransitions: Int,
    val gates: Int,
)

/**
 * Proof transition: canonical program operations -> `Kvp004ProgramProofResult`.
 * Establishes the four exact rejection fixtures and complete admitted program counts, order, waves,
 * and terminal. Expected mismatch is finite [Kvp004ProgramProofFailure]; raw values leave only at
 * the generated report boundary.
 */
internal fun deriveKvp004ProgramProof(): Kvp004ProgramProofResult {
    val definition = KastVfsPassiveReusedIndexProgram.definition
    val first = definition.tasks.first()
    val incomplete = definition.copy(
        tasks = definition.tasks.map { if (it.id == first.id) it.copy(title = "") else it },
    )
    if (admitCanonicalProgram(incomplete) != rejectedProgram(
            CanonicalProgramFailure.INCOMPLETE_TASK_CONTRACT,
        )
    ) return proofMismatch()
    val duplicateOwner = definition.copy(
        authorities = definition.authorities + definition.authorities.first(),
    )
    if (admitCanonicalProgram(duplicateOwner) != rejectedProgram(
            CanonicalProgramFailure.DUPLICATE_AUTHORITY_OWNER,
        )
    ) return proofMismatch()
    val untraced = definition.copy(
        requirements = definition.requirements +
            Requirement(RequirementId("KVP-REQ-999"), "Fixture remains untraced."),
    )
    if (admitCanonicalProgram(untraced) != rejectedProgram(
            CanonicalProgramFailure.UNTRACED_REQUIREMENT,
        )
    ) return proofMismatch()
    val cyclic = definition.copy(
        tasks = definition.tasks.map { task ->
            if (task.id == first.id) {
                task.copy(
                    dependencies = DependencyExpression(
                        EdgeKind.REQUIRES_ALL,
                        setOf(definition.terminalTask),
                    ),
                )
            } else {
                task
            }
        },
    )
    if (admitCanonicalProgram(cyclic) != rejectedProgram(CanonicalProgramFailure.CYCLE)) {
        return proofMismatch()
    }
    val admitted = when (val result = admitCanonicalProgram(definition)) {
        is CanonicalProgramAdmission.Complete -> result.program
        is CanonicalProgramAdmission.Rejected -> return proofMismatch()
    }
    if (admitted.counts() != expectedKvp004Counts ||
        admitted.order != expectedKvp004Order ||
        admitted.program.terminalTask != TaskId("KVP-043") ||
        admitted.waves.values.max() + 1 != 37
    ) return proofMismatch()
    return Kvp004ProgramProofResult.Complete(
        Kvp004ProgramProof(expectedKvp004RejectedCases, admitted),
    )
}

private fun rejectedProgram(failure: CanonicalProgramFailure) =
    CanonicalProgramAdmission.Rejected(failure)

private fun proofMismatch() =
    Kvp004ProgramProofResult.Rejected(Kvp004ProgramProofFailure.ADMISSION_MISMATCH)

private fun ValidatedProgram.counts() = Kvp004ProgramCounts(
    tasks = program.tasks.size,
    requirements = program.requirements.size,
    modules = program.modules.size,
    authorities = program.authorities.size,
    effects = program.effects.size,
    processNodes = program.processNodes.size,
    processTransitions = program.processTransitions.size,
    gates = program.gates.size,
)

/**
 * Proof transition: `Kvp004ProgramProof -> String`.
 * Preserves all canonical program proof fields in generated JSON. No expected failure remains;
 * raw JSON is emitted only at the Gradle report boundary.
 */
internal fun encodeKvp004ProgramProof(proof: Kvp004ProgramProof): String {
    val admitted = proof.program
    val counts = admitted.counts()
    return kvp004ProofJson.encodeToString(
        Kvp004ProgramProofJsonDocument.serializer(),
        Kvp004ProgramProofJsonDocument(
            schemaVersion = 1,
            taskId = "KVP-004",
            outcome = "COMPLETE",
            rejectedCases = proof.rejectedCases.map { it.name }.sorted(),
            taskCount = counts.tasks,
            requirementCount = counts.requirements,
            moduleCount = counts.modules,
            authorityCount = counts.authorities,
            effectCount = counts.effects,
            processNodeCount = counts.processNodes,
            processTransitionCount = counts.processTransitions,
            gateCount = counts.gates,
            terminalTaskId = admitted.program.terminalTask.value,
            taskOrder = admitted.order.map { it.value },
            waveCount = admitted.waves.values.max() + 1,
        ),
    ) + "\n"
}

/**
 * Proof transition: report JSON `String -> Kvp004ProgramProofResult`.
 * Establishes exact schema, task, outcome, negative cases, program counts, order, terminal, and
 * waves. Expected malformed or mismatched evidence is finite [Kvp004ProgramProofFailure].
 */
internal fun decodeKvp004ProgramProof(raw: String): Kvp004ProgramProofResult {
    val document = try {
        kvp004ProofJson.decodeFromString(Kvp004ProgramProofJsonDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp004ProgramProofResult.Rejected(
            Kvp004ProgramProofFailure.MALFORMED_DOCUMENT,
        )
    }
    val taskOrder = document.taskOrder.map { rawTaskId ->
        when (val refinement = refineTaskId(rawTaskId)) {
            is DeliveryRefinement.Complete -> refinement.value
            is DeliveryRefinement.Rejected -> return Kvp004ProgramProofResult.Rejected(
                Kvp004ProgramProofFailure.ORDER_MISMATCH,
            )
        }
    }
    val counts = Kvp004ProgramCounts(
        document.taskCount,
        document.requirementCount,
        document.moduleCount,
        document.authorityCount,
        document.effectCount,
        document.processNodeCount,
        document.processTransitionCount,
        document.gateCount,
    )
    val failure = when {
        document.schemaVersion != 1 -> Kvp004ProgramProofFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-004" -> Kvp004ProgramProofFailure.TASK_ID_MISMATCH
        document.outcome != "COMPLETE" -> Kvp004ProgramProofFailure.OUTCOME_MISMATCH
        document.rejectedCases != expectedKvp004RejectedCases.map { it.name }.sorted() ->
            Kvp004ProgramProofFailure.REJECTED_CASES_MISMATCH
        counts != expectedKvp004Counts -> Kvp004ProgramProofFailure.PROGRAM_COUNTS_MISMATCH
        document.terminalTaskId != "KVP-043" -> Kvp004ProgramProofFailure.TERMINAL_MISMATCH
        taskOrder != expectedKvp004Order ->
            Kvp004ProgramProofFailure.ORDER_MISMATCH
        document.waveCount != 37 -> Kvp004ProgramProofFailure.WAVE_MISMATCH
        else -> return deriveKvp004ProgramProof()
    }
    return Kvp004ProgramProofResult.Rejected(failure)
}

abstract class Kvp004ReceiptTaskBase : Kvp003ReceiptTaskBase() {
    @get:Input abstract val programTaskId: Property<String>
    @get:Input abstract val programRedGateId: Property<String>
    @get:Input abstract val programGreenGateId: Property<String>
    @get:Input abstract val programCompletionGateId: Property<String>
    @get:Input abstract val programRedReceiptId: Property<String>
    @get:Input abstract val programGreenReceiptId: Property<String>
    @get:Input abstract val programCompletionReceiptId: Property<String>
    @get:Input abstract val programRedCommand: Property<String>
    @get:Input abstract val programGreenCommand: Property<String>
    @get:Input abstract val programCompletionCommand: Property<String>
    @get:Input abstract val programTaskInputDigest: Property<String>
    @get:Input abstract val programCompletionInputDigest: Property<String>
    @get:Input abstract val programProofReportPath: Property<String>
    @get:InputFile abstract val graphRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val graphGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val graphProofReportFile: RegularFileProperty
    @get:InputFile abstract val graphCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-004 command plus fixed test filter -> successful gate process.
     * Establishes exact command equality and zero exit; mismatch is [ProofReceiptFailure]. Raw
     * process arguments are exposed only at Gradle's process boundary.
     */
    internal fun runProgramGate(command: String, filter: String) {
        val expected = "./gradlew :build-logic:test --tests \"$filter\""
        if (command != expected) {
            rejectReceipt("KVP-004 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine("./gradlew", ":build-logic:test", "--tests", filter)
        }
    }

    /**
     * Proof transition: configured KVP-004 inputs plus `AuthorityGitRevision` ->
     * `Kvp004ReceiptContexts`. Establishes both directly admitted KVP-002 and KVP-003 completions;
     * expected receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun programContexts(head: AuthorityGitRevision): Kvp004ReceiptContexts {
        val graph = graphContexts(head)
        val proof = graph.reportProof()
        val red = graph.boundary.admit(
            graphRedReceiptFile.get().asFile.toPath(),
            graph.redExpectation(proof),
        )
        val green = graph.boundary.admit(
            graphGreenReceiptFile.get().asFile.toPath(),
            graph.greenExpectation(red, proof),
        )
        val completion = graph.boundary.admit(
            graphCompletionReceiptFile.get().asFile.toPath(),
            graph.completionExpectation(red, green),
        )
        return Kvp004ReceiptContexts(
            graph.boundary,
            listOf(graph.predecessor, completion),
            programTaskId.get(),
            programRedGateId.get(),
            programGreenGateId.get(),
            programCompletionGateId.get(),
            programRedReceiptId.get(),
            programGreenReceiptId.get(),
            programCompletionReceiptId.get(),
            programRedCommand.get(),
            programGreenCommand.get(),
            programCompletionCommand.get(),
            programTaskInputDigest.get(),
            programCompletionInputDigest.get(),
            programProofReportPath.get(),
        )
    }
}
