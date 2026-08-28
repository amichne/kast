package support.delivery

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import support.architecture.NoDefaultRuntimeFallbackGate
import support.architecture.NoDefaultRuntimeFallbackGateOutcome
import support.architecture.NoDefaultRuntimeFallbackReportAdmission
import support.architecture.admitNoDefaultRuntimeFallbackReport

@Serializable
private data class Kvp027GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp027GateCaseDocument,
    val legalPath: Kvp027GateCaseDocument,
    val forbiddenWork: List<Kvp027ForbiddenWorkEvidence>,
    val executedTestCount: Int,
    val suiteFailureCount: Int,
    val misuseReportDigest: String,
    val legalReportDigest: String,
)

@Serializable
private data class Kvp027GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp027SemanticOutcome,
)

@Serializable
internal data class Kvp027ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp027SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp027TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    GATE_REPORT_REJECTED,
    TEST_SUITE_REJECTED,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp027ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp027ForbiddenWorkEvidence>,
)

internal sealed interface Kvp027TestEvidenceAdmission {
    data class Complete(val evidence: Kvp027ProofCaseExpectation) :
        Kvp027TestEvidenceAdmission
    data class Rejected(val failure: Kvp027TestEvidenceFailure) :
        Kvp027TestEvidenceAdmission
}

private data class Kvp027GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp027ForbiddenWorkEvidence>,
)

private sealed interface Kvp027GateConfigurationAdmission {
    data class Complete(val configuration: Kvp027GateConfiguration) :
        Kvp027GateConfigurationAdmission
    data class Rejected(val failure: Kvp027TestEvidenceFailure) :
        Kvp027GateConfigurationAdmission
}

private val kvp027EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Admits graph-named KVP-027 misuse, legal, and test evidence")
abstract class Kvp027AtomicProofEvidenceTask : DefaultTask() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseCommand: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathCommand: Property<String>
    @get:Input abstract val forbiddenWorkDescriptions: ListProperty<String>
    @get:InputFile abstract val misuseReportFile: RegularFileProperty
    @get:InputFile abstract val legalReportFile: RegularFileProperty
    @get:InputDirectory abstract val testResultsDirectory: DirectoryProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    /** Configures every case field only from the canonical KVP-027 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp027GateConfiguration()) {
            is Kvp027GateConfigurationAdmission.Complete -> admitted.configuration
            is Kvp027GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-027 gate configuration rejected: ${admitted.failure}",
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
        val misuseRaw = read(misuseReportFile)
        val legalRaw = read(legalReportFile)
        val misuse = when (val admitted = admitNoDefaultRuntimeFallbackReport(misuseRaw)) {
            is NoDefaultRuntimeFallbackReportAdmission.Complete -> admitted.report
            is NoDefaultRuntimeFallbackReportAdmission.Rejected -> reject(
                "misuse report: ${admitted.failure}",
            )
        }
        val legal = when (val admitted = admitNoDefaultRuntimeFallbackReport(legalRaw)) {
            is NoDefaultRuntimeFallbackReportAdmission.Complete -> admitted.report
            is NoDefaultRuntimeFallbackReportAdmission.Rejected -> reject(
                "legal report: ${admitted.failure}",
            )
        }
        if (
            misuse.gate != NoDefaultRuntimeFallbackGate.MISUSE ||
            misuse.outcome != NoDefaultRuntimeFallbackGateOutcome.REJECTED ||
            legal.gate != NoDefaultRuntimeFallbackGate.LEGAL_PATH ||
            legal.outcome != NoDefaultRuntimeFallbackGateOutcome.COMPLETE
        ) reject("gate outcome mismatch")
        val testReports = Files.list(testResultsDirectory.get().asFile.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".xml") }
                .map(Files::readString)
                .toList()
        }
        val executed = testReports.sumOf { Regex("<testcase\\b").findAll(it).count() }
        val failures = testReports.sumOf {
            Regex("<(failure|error|skipped)\\b").findAll(it).count()
        }
        if (executed < 1 || failures != 0) reject("test suite: tests=$executed failures=$failures")
        val document = Kvp027GateEvidenceDocument(
            1,
            KVP027_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp027GateCaseDocument(
                misuseCaseId.get(),
                misuseCaseName.get(),
                Kvp027SemanticOutcome.REJECTED,
            ),
            Kvp027GateCaseDocument(
                legalPathCaseId.get(),
                legalPathCaseName.get(),
                Kvp027SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp027ForbiddenWorkEvidence(it, misuseCaseName.get())
            },
            executed,
            failures,
            sha256(misuseRaw).value,
            sha256(legalRaw).value,
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }

    private fun read(property: RegularFileProperty): String = when (
        val read = readBoundaryFile(property.get().asFile.toPath(), MAX_RECEIPT_EVIDENCE_BYTES)
    ) {
        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
        is BoundaryFileRead.Rejected -> reject("bounded report read: ${read.failure}")
    }

    private fun reject(reason: String): Nothing = throw GradleException(
        "KVP-027 evidence rejected: $reason",
    )
}

/**
 * Proof transition: gate-evidence JSON plus canonical graph packet ->
 * `Kvp027TestEvidenceAdmission`.
 *
 * Establishes both packet-selected cases, all forbidden-work mappings, successful CLI tests, and
 * canonical evidence encoding. Malformed or mismatched evidence remains finite rejection.
 */
internal fun admitKvp027TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp027TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp027GateConfiguration()) {
        is Kvp027GateConfigurationAdmission.Complete -> admitted.configuration
        is Kvp027GateConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp027EvidenceJson.decodeFromString(Kvp027GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp027TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp027TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP027_TASK_ID ->
            Kvp027TestEvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command,
            configuration.legalPath.command,
        ).sorted() -> Kvp027TestEvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp027SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp027SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ->
            Kvp027TestEvidenceFailure.CASE_MISMATCH
        document.executedTestCount < 1 || document.suiteFailureCount != 0 ->
            Kvp027TestEvidenceFailure.TEST_SUITE_REJECTED
        raw != encode(document) -> Kvp027TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp027TestEvidenceAdmission.Complete(expectation)
    else rejected(failure)
}

internal fun expectedKvp027ProofCases(packet: TaskPacket): Kvp027TestEvidenceAdmission =
    when (val admitted = packet.kvp027GateConfiguration()) {
        is Kvp027GateConfigurationAdmission.Complete ->
            Kvp027TestEvidenceAdmission.Complete(admitted.configuration.expectation())
        is Kvp027GateConfigurationAdmission.Rejected -> rejected(admitted.failure)
    }

private fun TaskPacket.kvp027GateConfiguration(): Kvp027GateConfigurationAdmission {
    if (task.id.value != KVP027_TASK_ID) return configurationRejected()
    if (proofCommand.misuse.command.taskPaths() != listOf(
            ":cli:verifyNoDefaultRuntimeFallbackNegative",
        ) || proofCommand.legalPath.command.taskPaths() != listOf(
            ":cli:test",
            ":cli:verifyNoDefaultRuntimeFallback",
        )
    ) return configurationRejected()
    return Kvp027GateConfigurationAdmission.Complete(
        Kvp027GateConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            task.forbiddenWork.map { Kvp027ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase) },
        ),
    )
}

private fun Kvp027GateConfiguration.expectation() = Kvp027ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
)

private fun String.taskPaths(): List<String> = removePrefix("./gradlew ")
    .split(' ')
    .filter { it.startsWith(":") }

private fun encode(document: Kvp027GateEvidenceDocument): String =
    kvp027EvidenceJson.encodeToString(Kvp027GateEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp027GateConfigurationAdmission.Rejected(
    Kvp027TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp027TestEvidenceFailure) =
    Kvp027TestEvidenceAdmission.Rejected(failure)

private const val KVP027_TASK_ID = "KVP-027"
