package support.delivery

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@Serializable
private data class Kvp028GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp028GateCaseDocument,
    val legalPath: Kvp028GateCaseDocument,
    val forbiddenWork: List<Kvp028ForbiddenWorkEvidence>,
    val misuseExecutedTestCount: Int,
    val legalExecutedTestCount: Int,
    val suiteFailureCount: Int,
)

@Serializable
private data class Kvp028GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp028SemanticOutcome,
)

@Serializable
internal data class Kvp028ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp028SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp028TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    TEST_SUITE_REJECTED,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp028ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp028ForbiddenWorkEvidence>,
)

internal sealed interface Kvp028TestEvidenceAdmission {
    data class Complete(val evidence: Kvp028ProofCaseExpectation) :
        Kvp028TestEvidenceAdmission
    data class Rejected(val failure: Kvp028TestEvidenceFailure) : Kvp028TestEvidenceAdmission
}

private data class Kvp028GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp028ForbiddenWorkEvidence>,
)

private sealed interface Kvp028GateConfigurationAdmission {
    data class Complete(val configuration: Kvp028GateConfiguration) :
        Kvp028GateConfigurationAdmission
    data class Rejected(val failure: Kvp028TestEvidenceFailure) :
        Kvp028GateConfigurationAdmission
}

private data class Kvp028SuiteObservation(
    val executed: Int,
    val failures: Int,
)

private val kvp028EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Admits graph-named KVP-028 misuse and legal test evidence")
abstract class Kvp028AtomicProofEvidenceTask : DefaultTask() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseCommand: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathCommand: Property<String>
    @get:Input abstract val forbiddenWorkDescriptions: ListProperty<String>
    @get:InputDirectory abstract val misuseResultsDirectory: DirectoryProperty
    @get:InputDirectory abstract val legalResultsDirectory: DirectoryProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    /** Configures every case field only from the canonical KVP-028 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp028GateConfiguration()) {
            is Kvp028GateConfigurationAdmission.Complete -> admitted.configuration
            is Kvp028GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-028 gate configuration rejected: ${admitted.failure}",
            )
        }
        misuseCaseId.set(configuration.misuse.gateId)
        misuseCaseName.set(configuration.misuse.namedCase)
        misuseCommand.set(configuration.misuse.command)
        legalPathCaseId.set(configuration.legalPath.gateId)
        legalPathCaseName.set(configuration.legalPath.namedCase)
        legalPathCommand.set(configuration.legalPath.command)
        forbiddenWorkDescriptions.set(configuration.forbiddenWork.map { it.description })
    }

    @TaskAction
    fun admit() {
        val misuse = observeSuite(misuseResultsDirectory.get().asFile.toPath())
        val legal = observeSuite(legalResultsDirectory.get().asFile.toPath())
        if (misuse.executed < 1 || legal.executed < 1 || misuse.failures + legal.failures != 0) {
            throw GradleException("KVP-028 tests rejected: misuse=$misuse legal=$legal")
        }
        val document = Kvp028GateEvidenceDocument(
            1,
            KVP028_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp028GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp028SemanticOutcome.REJECTED,
            ),
            Kvp028GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp028SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp028ForbiddenWorkEvidence(it, misuseCaseName.get())
            },
            misuse.executed,
            legal.executed,
            misuse.failures + legal.failures,
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }
}

/**
 * Proof transition: gate-evidence JSON plus canonical graph packet ->
 * `Kvp028TestEvidenceAdmission`.
 *
 * Establishes the packet-selected misuse/legal cases, every forbidden-work mapping, and successful
 * execution of both exact test selectors. Malformed or mismatched evidence remains finite data.
 */
internal fun admitKvp028TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp028TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp028GateConfiguration()) {
        is Kvp028GateConfigurationAdmission.Complete -> admitted.configuration
        is Kvp028GateConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp028EvidenceJson.decodeFromString(Kvp028GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp028TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp028TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP028_TASK_ID ->
            Kvp028TestEvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command,
            configuration.legalPath.command,
        ).sorted() -> Kvp028TestEvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp028SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp028SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ->
            Kvp028TestEvidenceFailure.CASE_MISMATCH
        document.misuseExecutedTestCount < 1 || document.legalExecutedTestCount < 1 ||
            document.suiteFailureCount != 0 -> Kvp028TestEvidenceFailure.TEST_SUITE_REJECTED
        raw != encode(document) -> Kvp028TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp028TestEvidenceAdmission.Complete(expectation)
    else rejected(failure)
}

internal fun expectedKvp028ProofCases(packet: TaskPacket): Kvp028TestEvidenceAdmission =
    when (val admitted = packet.kvp028GateConfiguration()) {
        is Kvp028GateConfigurationAdmission.Complete ->
            Kvp028TestEvidenceAdmission.Complete(admitted.configuration.expectation())
        is Kvp028GateConfigurationAdmission.Rejected -> rejected(admitted.failure)
    }

private fun TaskPacket.kvp028GateConfiguration(): Kvp028GateConfigurationAdmission {
    if (
        task.id.value != KVP028_TASK_ID ||
        proofCommand.misuse.command != "./gradlew ideHostedWorkspaceInspectNegativeProof" ||
        proofCommand.legalPath.command != "./gradlew ideHostedWorkspaceInspectAcceptance"
    ) return configurationRejected()
    return Kvp028GateConfigurationAdmission.Complete(
        Kvp028GateConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            task.forbiddenWork.map { Kvp028ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase) },
        ),
    )
}

private fun Kvp028GateConfiguration.expectation() = Kvp028ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
)

private fun observeSuite(directory: Path): Kvp028SuiteObservation {
    val reports = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".xml") }.map(Files::readString).toList()
    }
    return Kvp028SuiteObservation(
        reports.sumOf { Regex("<testcase\\b").findAll(it).count() },
        reports.sumOf { Regex("<(failure|error|skipped)\\b").findAll(it).count() },
    )
}

private fun encode(document: Kvp028GateEvidenceDocument): String =
    kvp028EvidenceJson.encodeToString(Kvp028GateEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp028GateConfigurationAdmission.Rejected(
    Kvp028TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp028TestEvidenceFailure) =
    Kvp028TestEvidenceAdmission.Rejected(failure)

private const val KVP028_TASK_ID = "KVP-028"
