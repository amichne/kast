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
import support.architecture.IdeReadForbiddenAuthority
import support.plugin.ideHostedNegativeCases

@Serializable
private data class Kvp032GateEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedCommands: List<String>,
    val misuse: Kvp032GateCaseDocument,
    val legalPath: Kvp032GateCaseDocument,
    val forbiddenWork: List<Kvp032ForbiddenWorkEvidence>,
    val negativeFixtureCount: Int,
    val proofReportDigest: String,
)

@Serializable
private data class Kvp032GateCaseDocument(
    val caseId: String,
    val name: String,
    val outcome: Kvp032SemanticOutcome,
)

@Serializable
internal data class Kvp032ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
)

@Serializable internal enum class Kvp032SemanticOutcome { REJECTED, COMPLETE }

internal enum class Kvp032EvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    CASE_MISMATCH,
    PROOF_REPORT_MISMATCH,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp032ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val forbiddenWork: List<Kvp032ForbiddenWorkEvidence>,
    val negativeFixtureCount: Int,
)

internal sealed interface Kvp032EvidenceAdmission {
    data class Complete(val evidence: Kvp032ProofCaseExpectation) : Kvp032EvidenceAdmission
    data class Rejected(val failure: Kvp032EvidenceFailure) : Kvp032EvidenceAdmission
}

private data class Kvp032GateConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val forbiddenWork: List<Kvp032ForbiddenWorkEvidence>,
)

private sealed interface Kvp032GateConfigurationAdmission {
    data class Complete(val configuration: Kvp032GateConfiguration) :
        Kvp032GateConfigurationAdmission
    data object Rejected : Kvp032GateConfigurationAdmission
}

private val kvp032EvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Binds graph-named KVP-032 cases to the static-safety report")
abstract class Kvp032AtomicProofEvidenceTask : DefaultTask() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseCommand: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathCommand: Property<String>
    @get:Input abstract val forbiddenWorkDescriptions: ListProperty<String>
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    /** Configures every case field only from the canonical graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp032GateConfiguration()) {
            is Kvp032GateConfigurationAdmission.Complete -> admitted.configuration
            Kvp032GateConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-032 gate configuration rejected",
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
        val report = when (val read = readBoundaryFile(
            proofReportFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )) {
            is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
            is BoundaryFileRead.Rejected -> throw GradleException(
                "KVP-032 static-safety report rejected: ${read.failure}",
            )
        }
        val document = Kvp032GateEvidenceDocument(
            1,
            KVP032_TASK_ID,
            listOf(misuseCommand.get(), legalPathCommand.get()).sorted(),
            Kvp032GateCaseDocument(
                misuseCaseId.get(), misuseCaseName.get(), Kvp032SemanticOutcome.REJECTED,
            ),
            Kvp032GateCaseDocument(
                legalPathCaseId.get(), legalPathCaseName.get(), Kvp032SemanticOutcome.COMPLETE,
            ),
            forbiddenWorkDescriptions.get().map {
                Kvp032ForbiddenWorkEvidence(it, misuseCaseName.get())
            },
            IdeReadForbiddenAuthority.entries.size + ideHostedNegativeCases().size,
            sha256(report).value,
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }
}

/**
 * Proof transition: gate-evidence JSON, canonical packet, and static-safety report ->
 * `Kvp032EvidenceAdmission`.
 *
 * Establishes the graph-selected misuse/legal outcomes, every forbidden-work mapping, the fixed
 * negative-fixture cardinality, and the exact legal report digest. Malformed or mismatched evidence
 * remains finite data; raw JSON is permitted only here.
 */
internal fun admitKvp032Evidence(
    raw: String,
    packet: TaskPacket,
    proofReport: String,
): Kvp032EvidenceAdmission {
    val configuration = when (val admitted = packet.kvp032GateConfiguration()) {
        is Kvp032GateConfigurationAdmission.Complete -> admitted.configuration
        Kvp032GateConfigurationAdmission.Rejected -> return evidenceRejected(
            Kvp032EvidenceFailure.PROTOCOL_MISMATCH,
        )
    }
    val document = try {
        kvp032EvidenceJson.decodeFromString(Kvp032GateEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return evidenceRejected(Kvp032EvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return evidenceRejected(Kvp032EvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expectation = configuration.expectation()
    val failure = when {
        document.schemaVersion != 1 || document.taskId != KVP032_TASK_ID ->
            Kvp032EvidenceFailure.TASK_MISMATCH
        document.selectedCommands != listOf(
            configuration.misuse.command, configuration.legalPath.command,
        ).sorted() -> Kvp032EvidenceFailure.PROTOCOL_MISMATCH
        document.misuse.name != expectation.misuseName ||
            document.misuse.outcome != Kvp032SemanticOutcome.REJECTED ||
            document.legalPath.name != expectation.legalPathName ||
            document.legalPath.outcome != Kvp032SemanticOutcome.COMPLETE ||
            document.forbiddenWork != expectation.forbiddenWork ||
            document.negativeFixtureCount != expectation.negativeFixtureCount ->
            Kvp032EvidenceFailure.CASE_MISMATCH
        document.proofReportDigest != sha256(proofReport).value ->
            Kvp032EvidenceFailure.PROOF_REPORT_MISMATCH
        raw != encode(document) -> Kvp032EvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) Kvp032EvidenceAdmission.Complete(expectation)
    else evidenceRejected(failure)
}

/** Canonical `TaskPacket -> Kvp032EvidenceAdmission`; establishes both named case expectations. */
internal fun expectedKvp032ProofCases(packet: TaskPacket): Kvp032EvidenceAdmission =
    when (val admitted = packet.kvp032GateConfiguration()) {
        is Kvp032GateConfigurationAdmission.Complete ->
            Kvp032EvidenceAdmission.Complete(admitted.configuration.expectation())
        Kvp032GateConfigurationAdmission.Rejected -> evidenceRejected(
            Kvp032EvidenceFailure.PROTOCOL_MISMATCH,
        )
    }

/** `TaskPacket -> Kvp032GateConfigurationAdmission`; excludes every noncanonical command shape. */
private fun TaskPacket.kvp032GateConfiguration(): Kvp032GateConfigurationAdmission {
    if (
        task.id.value != KVP032_TASK_ID ||
        proofCommand.misuse.command != "./gradlew verifyVfsPassiveReadNegative" ||
        proofCommand.legalPath.command !=
        "./gradlew verifyVfsPassiveRead verifyKastModuleGraph verifyForbiddenEffects"
    ) return Kvp032GateConfigurationAdmission.Rejected
    return Kvp032GateConfigurationAdmission.Complete(Kvp032GateConfiguration(
        proofCommand.misuse,
        proofCommand.legalPath,
        task.forbiddenWork.map {
            Kvp032ForbiddenWorkEvidence(it, proofCommand.misuse.namedCase)
        },
    ))
}

private fun Kvp032GateConfiguration.expectation() = Kvp032ProofCaseExpectation(
    misuse.namedCase,
    legalPath.namedCase,
    forbiddenWork,
    IdeReadForbiddenAuthority.entries.size + ideHostedNegativeCases().size,
)

private fun encode(document: Kvp032GateEvidenceDocument): String =
    kvp032EvidenceJson.encodeToString(Kvp032GateEvidenceDocument.serializer(), document) + "\n"
private fun evidenceRejected(failure: Kvp032EvidenceFailure) =
    Kvp032EvidenceAdmission.Rejected(failure)
private const val KVP032_TASK_ID = "KVP-032"
