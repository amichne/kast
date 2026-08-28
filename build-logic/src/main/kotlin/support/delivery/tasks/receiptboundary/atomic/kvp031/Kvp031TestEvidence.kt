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
private data class Kvp031GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp031GateCaseDocument,
    val legalPath: Kvp031GateCaseDocument,
    val forbiddenWork: List<Kvp031ForbiddenWorkEvidence>,
    val misuseExecutedTestCount: Int,
    val legalExecutedTestCount: Int,
    val suiteFailureCount: Int,
)

@Serializable
private data class Kvp031GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp031SemanticOutcome,
)

@Serializable
internal data class Kvp031ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp031SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp031TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    TEST_SUITE_REJECTED,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp031ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp031ForbiddenWorkEvidence>,
)

internal sealed interface Kvp031TestEvidenceAdmission {
    data class Complete(val evidence: Kvp031ProofCaseExpectation) :
        Kvp031TestEvidenceAdmission
    data class Rejected(val failure: Kvp031TestEvidenceFailure) : Kvp031TestEvidenceAdmission
}

private data class Kvp031GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp031ForbiddenWorkEvidence>,
)

private sealed interface Kvp031GateConfigurationAdmission {
    data class Complete(val configuration: Kvp031GateConfiguration) :
        Kvp031GateConfigurationAdmission
    data class Rejected(val failure: Kvp031TestEvidenceFailure) :
        Kvp031GateConfigurationAdmission
}

private data class Kvp031SuiteObservation(
    val executed: Int,
    val failures: Int,
)

private val kvp031EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Admits graph-named KVP-031 misuse and legal test evidence")
abstract class Kvp031AtomicProofEvidenceTask : DefaultTask() {
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

    /** Configures every case field only from the canonical KVP-031 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp031GateConfiguration()) {
            is Kvp031GateConfigurationAdmission.Complete -> admitted.configuration
            is Kvp031GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-031 gate configuration rejected: ${admitted.failure}",
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
            throw GradleException("KVP-031 tests rejected: misuse=$misuse legal=$legal")
        }
        val document = Kvp031GateEvidenceDocument(
            1,
            KVP031_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp031GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp031SemanticOutcome.REJECTED,
            ),
            Kvp031GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp031SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp031ForbiddenWorkEvidence(it, misuseCaseName.get())
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
 * `Kvp031TestEvidenceAdmission`.
 *
 * Establishes the packet-selected misuse/legal cases, every forbidden-work mapping, and successful
 * execution of both exact test selectors. Malformed or mismatched evidence remains finite data.
 */
internal fun admitKvp031TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp031TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp031GateConfiguration()) {
        is Kvp031GateConfigurationAdmission.Complete -> admitted.configuration
        is Kvp031GateConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp031EvidenceJson.decodeFromString(Kvp031GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp031TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp031TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP031_TASK_ID ->
            Kvp031TestEvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command,
            configuration.legalPath.command,
        ).sorted() -> Kvp031TestEvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp031SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp031SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ->
            Kvp031TestEvidenceFailure.CASE_MISMATCH
        document.misuseExecutedTestCount < 1 || document.legalExecutedTestCount < 1 ||
            document.suiteFailureCount != 0 -> Kvp031TestEvidenceFailure.TEST_SUITE_REJECTED
        raw != encode(document) -> Kvp031TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp031TestEvidenceAdmission.Complete(expectation)
    else rejected(failure)
}

internal fun expectedKvp031ProofCases(packet: TaskPacket): Kvp031TestEvidenceAdmission =
    when (val admitted = packet.kvp031GateConfiguration()) {
        is Kvp031GateConfigurationAdmission.Complete ->
            Kvp031TestEvidenceAdmission.Complete(admitted.configuration.expectation())
        is Kvp031GateConfigurationAdmission.Rejected -> rejected(admitted.failure)
    }

private fun TaskPacket.kvp031GateConfiguration(): Kvp031GateConfigurationAdmission {
    if (
        task.id.value != KVP031_TASK_ID ||
        proofCommand.misuse.command != "./gradlew ideHostedSymbolDescribeNegativeProof" ||
        proofCommand.legalPath.command != "./gradlew ideHostedSymbolDescribeAcceptance"
    ) return configurationRejected()
    return Kvp031GateConfigurationAdmission.Complete(
        Kvp031GateConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            task.forbiddenWork.map { Kvp031ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase) },
        ),
    )
}

private fun Kvp031GateConfiguration.expectation() = Kvp031ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
)

private fun observeSuite(directory: Path): Kvp031SuiteObservation {
    val reports = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".xml") }.map(Files::readString).toList()
    }
    return Kvp031SuiteObservation(
        reports.sumOf { Regex("<testcase\\b").findAll(it).count() },
        reports.sumOf { Regex("<(failure|error|skipped)\\b").findAll(it).count() },
    )
}

private fun encode(document: Kvp031GateEvidenceDocument): String =
    kvp031EvidenceJson.encodeToString(Kvp031GateEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp031GateConfigurationAdmission.Rejected(
    Kvp031TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp031TestEvidenceFailure) =
    Kvp031TestEvidenceAdmission.Rejected(failure)

private const val KVP031_TASK_ID = "KVP-031"
