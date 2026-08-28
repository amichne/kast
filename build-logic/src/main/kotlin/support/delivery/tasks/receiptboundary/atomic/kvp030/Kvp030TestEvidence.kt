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
private data class Kvp030GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp030GateCaseDocument,
    val legalPath: Kvp030GateCaseDocument,
    val forbiddenWork: List<Kvp030ForbiddenWorkEvidence>,
    val misuseExecutedTestCount: Int,
    val legalExecutedTestCount: Int,
    val suiteFailureCount: Int,
)

@Serializable
private data class Kvp030GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp030SemanticOutcome,
)

@Serializable
internal data class Kvp030ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp030SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp030TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    TEST_SUITE_REJECTED,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp030ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp030ForbiddenWorkEvidence>,
)

internal sealed interface Kvp030TestEvidenceAdmission {
    data class Complete(val evidence: Kvp030ProofCaseExpectation) :
        Kvp030TestEvidenceAdmission
    data class Rejected(val failure: Kvp030TestEvidenceFailure) : Kvp030TestEvidenceAdmission
}

private data class Kvp030GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp030ForbiddenWorkEvidence>,
)

private sealed interface Kvp030GateConfigurationAdmission {
    data class Complete(val configuration: Kvp030GateConfiguration) :
        Kvp030GateConfigurationAdmission
    data class Rejected(val failure: Kvp030TestEvidenceFailure) :
        Kvp030GateConfigurationAdmission
}

private data class Kvp030SuiteObservation(
    val executed: Int,
    val failures: Int,
)

private val kvp030EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Admits graph-named KVP-030 misuse and legal test evidence")
abstract class Kvp030AtomicProofEvidenceTask : DefaultTask() {
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

    /** Configures every case field only from the canonical KVP-030 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp030GateConfiguration()) {
            is Kvp030GateConfigurationAdmission.Complete -> admitted.configuration
            is Kvp030GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-030 gate configuration rejected: ${admitted.failure}",
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
            throw GradleException("KVP-030 tests rejected: misuse=$misuse legal=$legal")
        }
        val document = Kvp030GateEvidenceDocument(
            1,
            KVP030_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp030GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp030SemanticOutcome.REJECTED,
            ),
            Kvp030GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp030SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp030ForbiddenWorkEvidence(it, misuseCaseName.get())
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
 * `Kvp030TestEvidenceAdmission`.
 *
 * Establishes the packet-selected misuse/legal cases, every forbidden-work mapping, and successful
 * execution of both exact test selectors. Malformed or mismatched evidence remains finite data.
 */
internal fun admitKvp030TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp030TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp030GateConfiguration()) {
        is Kvp030GateConfigurationAdmission.Complete -> admitted.configuration
        is Kvp030GateConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp030EvidenceJson.decodeFromString(Kvp030GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp030TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp030TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP030_TASK_ID ->
            Kvp030TestEvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command,
            configuration.legalPath.command,
        ).sorted() -> Kvp030TestEvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp030SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp030SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ->
            Kvp030TestEvidenceFailure.CASE_MISMATCH
        document.misuseExecutedTestCount < 1 || document.legalExecutedTestCount < 1 ||
            document.suiteFailureCount != 0 -> Kvp030TestEvidenceFailure.TEST_SUITE_REJECTED
        raw != encode(document) -> Kvp030TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp030TestEvidenceAdmission.Complete(expectation)
    else rejected(failure)
}

internal fun expectedKvp030ProofCases(packet: TaskPacket): Kvp030TestEvidenceAdmission =
    when (val admitted = packet.kvp030GateConfiguration()) {
        is Kvp030GateConfigurationAdmission.Complete ->
            Kvp030TestEvidenceAdmission.Complete(admitted.configuration.expectation())
        is Kvp030GateConfigurationAdmission.Rejected -> rejected(admitted.failure)
    }

private fun TaskPacket.kvp030GateConfiguration(): Kvp030GateConfigurationAdmission {
    if (
        task.id.value != KVP030_TASK_ID ||
        proofCommand.misuse.command != "./gradlew ideHostedSymbolResolveNegativeProof" ||
        proofCommand.legalPath.command != "./gradlew ideHostedSymbolResolveAcceptance"
    ) return configurationRejected()
    return Kvp030GateConfigurationAdmission.Complete(
        Kvp030GateConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            task.forbiddenWork.map { Kvp030ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase) },
        ),
    )
}

private fun Kvp030GateConfiguration.expectation() = Kvp030ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
)

private fun observeSuite(directory: Path): Kvp030SuiteObservation {
    val reports = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".xml") }.map(Files::readString).toList()
    }
    return Kvp030SuiteObservation(
        reports.sumOf { Regex("<testcase\\b").findAll(it).count() },
        reports.sumOf { Regex("<(failure|error|skipped)\\b").findAll(it).count() },
    )
}

private fun encode(document: Kvp030GateEvidenceDocument): String =
    kvp030EvidenceJson.encodeToString(Kvp030GateEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp030GateConfigurationAdmission.Rejected(
    Kvp030TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp030TestEvidenceFailure) =
    Kvp030TestEvidenceAdmission.Rejected(failure)

private const val KVP030_TASK_ID = "KVP-030"
