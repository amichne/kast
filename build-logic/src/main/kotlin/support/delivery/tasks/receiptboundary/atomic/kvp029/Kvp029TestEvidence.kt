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
private data class Kvp029GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp029GateCaseDocument,
    val legalPath: Kvp029GateCaseDocument,
    val forbiddenWork: List<Kvp029ForbiddenWorkEvidence>,
    val misuseExecutedTestCount: Int,
    val legalExecutedTestCount: Int,
    val suiteFailureCount: Int,
)

@Serializable
private data class Kvp029GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp029SemanticOutcome,
)

@Serializable
internal data class Kvp029ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp029SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp029TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    TEST_SUITE_REJECTED,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp029ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp029ForbiddenWorkEvidence>,
)

internal sealed interface Kvp029TestEvidenceAdmission {
    data class Complete(val evidence: Kvp029ProofCaseExpectation) :
        Kvp029TestEvidenceAdmission
    data class Rejected(val failure: Kvp029TestEvidenceFailure) : Kvp029TestEvidenceAdmission
}

private data class Kvp029GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp029ForbiddenWorkEvidence>,
)

private sealed interface Kvp029GateConfigurationAdmission {
    data class Complete(val configuration: Kvp029GateConfiguration) :
        Kvp029GateConfigurationAdmission
    data class Rejected(val failure: Kvp029TestEvidenceFailure) :
        Kvp029GateConfigurationAdmission
}

private data class Kvp029SuiteObservation(
    val executed: Int,
    val failures: Int,
)

private val kvp029EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Admits graph-named KVP-029 misuse and legal test evidence")
abstract class Kvp029AtomicProofEvidenceTask : DefaultTask() {
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

    /** Configures every case field only from the canonical KVP-029 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp029GateConfiguration()) {
            is Kvp029GateConfigurationAdmission.Complete -> admitted.configuration
            is Kvp029GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-029 gate configuration rejected: ${admitted.failure}",
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
            throw GradleException("KVP-029 tests rejected: misuse=$misuse legal=$legal")
        }
        val document = Kvp029GateEvidenceDocument(
            1,
            KVP029_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp029GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp029SemanticOutcome.REJECTED,
            ),
            Kvp029GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp029SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp029ForbiddenWorkEvidence(it, misuseCaseName.get())
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
 * `Kvp029TestEvidenceAdmission`.
 *
 * Establishes the packet-selected misuse/legal cases, every forbidden-work mapping, and successful
 * execution of both exact test selectors. Malformed or mismatched evidence remains finite data.
 */
internal fun admitKvp029TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp029TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp029GateConfiguration()) {
        is Kvp029GateConfigurationAdmission.Complete -> admitted.configuration
        is Kvp029GateConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp029EvidenceJson.decodeFromString(Kvp029GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp029TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp029TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP029_TASK_ID ->
            Kvp029TestEvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command,
            configuration.legalPath.command,
        ).sorted() -> Kvp029TestEvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp029SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp029SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ->
            Kvp029TestEvidenceFailure.CASE_MISMATCH
        document.misuseExecutedTestCount < 1 || document.legalExecutedTestCount < 1 ||
            document.suiteFailureCount != 0 -> Kvp029TestEvidenceFailure.TEST_SUITE_REJECTED
        raw != encode(document) -> Kvp029TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp029TestEvidenceAdmission.Complete(expectation)
    else rejected(failure)
}

internal fun expectedKvp029ProofCases(packet: TaskPacket): Kvp029TestEvidenceAdmission =
    when (val admitted = packet.kvp029GateConfiguration()) {
        is Kvp029GateConfigurationAdmission.Complete ->
            Kvp029TestEvidenceAdmission.Complete(admitted.configuration.expectation())
        is Kvp029GateConfigurationAdmission.Rejected -> rejected(admitted.failure)
    }

private fun TaskPacket.kvp029GateConfiguration(): Kvp029GateConfigurationAdmission {
    if (
        task.id.value != KVP029_TASK_ID ||
        proofCommand.misuse.command != "./gradlew ideHostedSymbolDiscoverNegativeProof" ||
        proofCommand.legalPath.command != "./gradlew ideHostedSymbolDiscoverAcceptance"
    ) return configurationRejected()
    return Kvp029GateConfigurationAdmission.Complete(
        Kvp029GateConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            task.forbiddenWork.map { Kvp029ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase) },
        ),
    )
}

private fun Kvp029GateConfiguration.expectation() = Kvp029ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
)

private fun observeSuite(directory: Path): Kvp029SuiteObservation {
    val reports = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".xml") }.map(Files::readString).toList()
    }
    return Kvp029SuiteObservation(
        reports.sumOf { Regex("<testcase\\b").findAll(it).count() },
        reports.sumOf { Regex("<(failure|error|skipped)\\b").findAll(it).count() },
    )
}

private fun encode(document: Kvp029GateEvidenceDocument): String =
    kvp029EvidenceJson.encodeToString(Kvp029GateEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp029GateConfigurationAdmission.Rejected(
    Kvp029TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp029TestEvidenceFailure) =
    Kvp029TestEvidenceAdmission.Rejected(failure)

private const val KVP029_TASK_ID = "KVP-029"
