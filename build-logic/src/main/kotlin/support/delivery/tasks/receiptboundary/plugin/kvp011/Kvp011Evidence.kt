package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import support.plugin.ideHostedNegativeCases

@Serializable
private data class Kvp011GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp011GateCaseDocument,
    val legalPath: Kvp011GateCaseDocument,
    val forbiddenWork: List<Kvp011ForbiddenWorkEvidence>,
    val negativeFixtureCount: Int,
    val layoutReportDigest: String,
)

@Serializable
private data class Kvp011GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp011SemanticOutcome,
)

@Serializable
internal data class Kvp011ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp011SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp011EvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    LAYOUT_REPORT_MISMATCH,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp011ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp011ForbiddenWorkEvidence>,
    val negativeFixtureCount: Int,
)

internal sealed interface Kvp011EvidenceAdmission {
    data class Complete(val evidence: Kvp011ProofCaseExpectation) : Kvp011EvidenceAdmission
    data class Rejected(val failure: Kvp011EvidenceFailure) : Kvp011EvidenceAdmission
}

private data class Kvp011GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp011ForbiddenWorkEvidence>,
)

private sealed interface Kvp011GateConfigurationAdmission {
    data class Complete(val configuration: Kvp011GateConfiguration) :
        Kvp011GateConfigurationAdmission
    data object Rejected : Kvp011GateConfigurationAdmission
}

private val kvp011EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Binds graph-named KVP-011 cases to the freshly generated layout report")
abstract class Kvp011AtomicProofEvidenceTask : DefaultTask() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseCommand: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathCommand: Property<String>
    @get:Input abstract val forbiddenWorkDescriptions: ListProperty<String>
    @get:InputFile abstract val layoutReportFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    /** Configures every case field only from the canonical graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp011GateConfiguration()) {
            is Kvp011GateConfigurationAdmission.Complete -> admitted.configuration
            Kvp011GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-011 gate configuration rejected",
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

    @TaskAction fun bind() {
        val layout = when (val read = readBoundaryFile(
            layoutReportFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )) {
            is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
            is BoundaryFileRead.Rejected -> throw GradleException(
                "KVP-011 layout report rejected: ${read.failure}",
            )
        }
        val document = Kvp011GateEvidenceDocument(
            1,
            KVP011_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp011GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp011SemanticOutcome.REJECTED,
            ),
            Kvp011GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp011SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp011ForbiddenWorkEvidence(it, misuseCaseName.get())
            },
            ideHostedNegativeCases().size,
            sha256(layout).value,
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }
}

/**
 * Proof transition: gate-evidence JSON, canonical packet, and physical layout report ->
 * `Kvp011EvidenceAdmission`.
 *
 * Establishes the graph-selected misuse/legal outcomes, every forbidden-work mapping, the fixed
 * negative-fixture cardinality, and the exact legal report digest. Malformed or mismatched evidence
 * remains finite data; raw JSON is permitted only here.
 */
internal fun admitKvp011Evidence(
    raw: String,
    packet: TaskPacket,
    layoutReport: String,
): Kvp011EvidenceAdmission {
    val configuration = when (val admitted = packet.kvp011GateConfiguration()) {
        is Kvp011GateConfigurationAdmission.Complete -> admitted.configuration
        Kvp011GateConfigurationAdmission.Rejected -> return evidenceRejected(
            Kvp011EvidenceFailure.PROTOCOL_MISMATCH,
        )
    }
    val document = try {
        kvp011EvidenceJson.decodeFromString(Kvp011GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return evidenceRejected(Kvp011EvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return evidenceRejected(Kvp011EvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP011_TASK_ID ->
            Kvp011EvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command, configuration.legalPath.command,
        ).sorted() -> Kvp011EvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp011SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp011SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ||
            document.negativeFixtureCount != expectation.negativeFixtureCount ->
            Kvp011EvidenceFailure.CASE_MISMATCH
        document.layoutReportDigest != sha256(layoutReport).value ->
            Kvp011EvidenceFailure.LAYOUT_REPORT_MISMATCH
        raw != encode(document) -> Kvp011EvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp011EvidenceAdmission.Complete(expectation)
    else evidenceRejected(failure)
}

/** Canonical `TaskPacket -> Kvp011EvidenceAdmission`; establishes both named case expectations. */
internal fun expectedKvp011ProofCases(packet: TaskPacket): Kvp011EvidenceAdmission =
    when (val admitted = packet.kvp011GateConfiguration()) {
        is Kvp011GateConfigurationAdmission.Complete ->
            Kvp011EvidenceAdmission.Complete(admitted.configuration.expectation())
        Kvp011GateConfigurationAdmission.Rejected -> evidenceRejected(
            Kvp011EvidenceFailure.PROTOCOL_MISMATCH,
        )
    }

/** `TaskPacket -> Kvp011GateConfigurationAdmission`; excludes every noncanonical command shape. */
private fun TaskPacket.kvp011GateConfiguration(): Kvp011GateConfigurationAdmission {
    if (
        task.id.value != KVP011_TASK_ID ||
        proofCommand.misuse.command != "./gradlew :ide-plugin:verifyPluginLayoutNegative" ||
        proofCommand.legalPath.command != "./gradlew :ide-plugin:verifyPluginLayout"
    ) return Kvp011GateConfigurationAdmission.Rejected
    return Kvp011GateConfigurationAdmission.Complete(Kvp011GateConfiguration(
        proofCommand.misuse,
        proofCommand.legalPath,
        task.forbiddenWork.map {
            Kvp011ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase)
        },
    ))
}

private fun Kvp011GateConfiguration.expectation() = Kvp011ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
    ideHostedNegativeCases().size,
)

private fun encode(document: Kvp011GateEvidenceDocument): String =
    kvp011EvidenceJson.encodeToString(Kvp011GateEvidenceDocument.serializer(), document) + "\n"
private fun evidenceRejected(failure: Kvp011EvidenceFailure) =
    Kvp011EvidenceAdmission.Rejected(failure)
private const val KVP011_TASK_ID = "KVP-011"
