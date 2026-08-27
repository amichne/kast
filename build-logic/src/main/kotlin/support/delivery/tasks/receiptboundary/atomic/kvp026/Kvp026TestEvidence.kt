package support.delivery

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test

@Serializable
private data class Kvp026TestEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedSelectors: List<String>,
    val executedTestCount: Int,
    val suiteFailureCount: Int,
    val misuse: Kvp026TestCaseDocument,
    val legalPath: Kvp026TestCaseDocument,
    val forbiddenWork: List<Kvp026ForbiddenWorkEvidence>,
)

@Serializable
private data class Kvp026TestCaseDocument(
    val caseId: String,
    val name: String,
    val semanticOutcome: Kvp026SemanticOutcome,
    val testResult: Kvp026ObservedTestResult,
)

@Serializable
internal data class Kvp026ForbiddenWorkEvidence(
    val description: String,
    val enforcementCaseName: String,
    val testResult: Kvp026ObservedTestResult,
)

@Serializable internal enum class Kvp026SemanticOutcome { REJECTED, COMPLETE }
@Serializable internal enum class Kvp026ObservedTestResult { SUCCESS }

internal enum class Kvp026TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    SELECTOR_MISMATCH,
    CASE_MISMATCH,
    CASE_NOT_SUCCESSFUL,
    SUITE_MISMATCH,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal data class Kvp026ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val executedTestCount: Int,
    val suiteFailureCount: Int,
    val forbiddenWork: List<Kvp026ForbiddenWorkEvidence>,
)

internal sealed interface Kvp026TestEvidenceAdmission {
    data class Complete(val evidence: Kvp026ProofCaseExpectation) :
        Kvp026TestEvidenceAdmission
    data class Rejected(val failure: Kvp026TestEvidenceFailure) :
        Kvp026TestEvidenceAdmission
}

private data class Kvp026TestConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val misuseSelector: String,
    val legalPathSelector: String,
    val forbiddenWork: List<Kvp026ForbiddenWorkEvidence>,
)

private sealed interface Kvp026TestConfigurationAdmission {
    data class Complete(val configuration: Kvp026TestConfiguration) :
        Kvp026TestConfigurationAdmission
    data class Rejected(val failure: Kvp026TestEvidenceFailure) :
        Kvp026TestConfigurationAdmission
}

private val kvp026TestEvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Executes the graph-named KVP-026 misuse and legal paths")
abstract class Kvp026AtomicProofTestTask : Test() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseSelector: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathSelector: Property<String>
    @get:Input abstract val forbiddenWorkDescriptions: ListProperty<String>
    @get:Input abstract val forbiddenWorkCaseNames: ListProperty<String>
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    init { doLast { writeCompleteEvidence() } }

    /** Configures selectors and named cases only from the canonical KVP-026 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp026TestConfiguration()) {
            is Kvp026TestConfigurationAdmission.Complete -> admitted.configuration
            is Kvp026TestConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-026 test configuration rejected: ${admitted.failure}",
            )
        }
        misuseCaseId.set(configuration.misuse.gateId)
        misuseCaseName.set(configuration.misuse.namedCase)
        misuseSelector.set(configuration.misuseSelector)
        legalPathCaseId.set(configuration.legalPath.gateId)
        legalPathCaseName.set(configuration.legalPath.namedCase)
        legalPathSelector.set(configuration.legalPathSelector)
        forbiddenWorkDescriptions.set(configuration.forbiddenWork.map { it.description })
        forbiddenWorkCaseNames.set(configuration.forbiddenWork.map { it.enforcementCaseName })
        filter.includeTestsMatching(configuration.misuseSelector)
        filter.includeTestsMatching(configuration.legalPathSelector)
        filter.setFailOnNoMatchingTests(true)
        reports.junitXml.required.set(true)
    }

    private fun writeCompleteEvidence() {
        val forbiddenEvidence = forbiddenWorkDescriptions.get()
            .zip(forbiddenWorkCaseNames.get()) { description, caseName ->
                Kvp026ForbiddenWorkEvidence(
                    description,
                    caseName,
                    Kvp026ObservedTestResult.SUCCESS,
                )
            }
        val expectedNames = buildSet {
            add(misuseCaseName.get())
            add(legalPathCaseName.get())
            addAll(forbiddenEvidence.map { it.enforcementCaseName })
        }
        val reports = Files.list(reports.junitXml.outputLocation.get().asFile.toPath()).use {
            paths -> paths.filter { it.fileName.toString().endsWith(".xml") }
                .map { path ->
                    when (val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)) {
                        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
                        is BoundaryFileRead.Rejected -> ""
                    }
                }.toList()
        }
        val missing = expectedNames.filterNot { name ->
            val encoded = name.xmlAttributeValue()
            reports.any { report ->
                "name=\"$encoded\"" in report || "name=\"$encoded()\"" in report
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException("KVP-026 named cases did not complete successfully: $missing")
        }
        val executed = reports.sumOf { Regex("<testcase\\b").findAll(it).count() }
        val failures = reports.sumOf {
            Regex("<(failure|error|skipped)\\b").findAll(it).count()
        }
        if (executed != KVP026_EXPECTED_TEST_COUNT || failures != 0) {
            throw GradleException("KVP-026 suite rejected: tests=$executed, failures=$failures")
        }
        val document = expectedDocument(configuration(), forbiddenEvidence)
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }

    private fun configuration() = Kvp026TestConfiguration(
        ProofCommand(misuseCaseId.get(), "", "", misuseCaseName.get()),
        ProofCommand(legalPathCaseId.get(), "", "", legalPathCaseName.get()),
        misuseSelector.get(),
        legalPathSelector.get(),
        emptyList(),
    )
}

/**
 * Proof transition: test-evidence JSON plus canonical graph packet ->
 * `Kvp026TestEvidenceAdmission`.
 *
 * Establishes successful execution of both packet-selected cases and every forbidden-work
 * enforcement mapping. Malformed or mismatched evidence remains finite rejection; raw JSON exists
 * only at this Gradle proof boundary.
 */
internal fun admitKvp026TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp026TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp026TestConfiguration()) {
        is Kvp026TestConfigurationAdmission.Complete -> admitted.configuration
        is Kvp026TestConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp026TestEvidenceJson.decodeFromString(Kvp026TestEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp026TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp026TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expected = expectedDocument(configuration, configuration.forbiddenWork)
    val failure = when {
        document != expected -> Kvp026TestEvidenceFailure.CASE_MISMATCH
        raw != encode(expected) -> Kvp026TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) {
        Kvp026TestEvidenceAdmission.Complete(expected.expectation())
    } else rejected(failure)
}

internal fun expectedKvp026ProofCases(packet: TaskPacket): Kvp026TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp026TestConfiguration()) {
        is Kvp026TestConfigurationAdmission.Complete -> admitted.configuration
        is Kvp026TestConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    return Kvp026TestEvidenceAdmission.Complete(
        expectedDocument(configuration, configuration.forbiddenWork).expectation(),
    )
}

private fun TaskPacket.kvp026TestConfiguration(): Kvp026TestConfigurationAdmission {
    if (task.id.value != KVP026_TASK_ID) return configurationRejected()
    val misuseSelector = selector(proofCommand.misuse.command) ?: return configurationRejected()
    val legalSelector = selector(proofCommand.legalPath.command) ?: return configurationRejected()
    if (task.forbiddenWork.toSet() != KVP026_FORBIDDEN_WORK_CASES.keys) {
        return configurationRejected()
    }
    val forbidden = task.forbiddenWork.map { description ->
        Kvp026ForbiddenWorkEvidence(
            description,
            KVP026_FORBIDDEN_WORK_CASES.getValue(description),
            Kvp026ObservedTestResult.SUCCESS,
        )
    }
    return Kvp026TestConfigurationAdmission.Complete(
        Kvp026TestConfiguration(
            proofCommand.misuse,
            proofCommand.legalPath,
            misuseSelector,
            legalSelector,
            forbidden,
        ),
    )
}

private fun selector(command: String): String? {
    val match = Regex("--tests \\\"([^\\\"]+)\\\"").find(command) ?: return null
    return match.groupValues[1].takeIf { match.range.last == command.lastIndex }
}

private fun expectedDocument(
    configuration: Kvp026TestConfiguration,
    forbidden: List<Kvp026ForbiddenWorkEvidence>,
) = Kvp026TestEvidenceDocument(
    1,
    KVP026_TASK_ID,
    listOf(configuration.misuseSelector, configuration.legalPathSelector).sorted(),
    KVP026_EXPECTED_TEST_COUNT,
    0,
    Kvp026TestCaseDocument(
        configuration.misuse.gateId,
        configuration.misuse.namedCase,
        Kvp026SemanticOutcome.REJECTED,
        Kvp026ObservedTestResult.SUCCESS,
    ),
    Kvp026TestCaseDocument(
        configuration.legalPath.gateId,
        configuration.legalPath.namedCase,
        Kvp026SemanticOutcome.COMPLETE,
        Kvp026ObservedTestResult.SUCCESS,
    ),
    forbidden,
)

private fun Kvp026TestEvidenceDocument.expectation() = Kvp026ProofCaseExpectation(
    misuse.name,
    legalPath.name,
    executedTestCount,
    suiteFailureCount,
    forbiddenWork,
)

private fun encode(document: Kvp026TestEvidenceDocument) =
    kvp026TestEvidenceJson.encodeToString(Kvp026TestEvidenceDocument.serializer(), document) + "\n"

private fun configurationRejected() = Kvp026TestConfigurationAdmission.Rejected(
    Kvp026TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun rejected(failure: Kvp026TestEvidenceFailure) =
    Kvp026TestEvidenceAdmission.Rejected(failure)

private fun String.xmlAttributeValue() = replace("&", "&amp;")
    .replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private const val KVP026_TASK_ID = "KVP-026"
private const val KVP026_EXPECTED_TEST_COUNT = 2

private val KVP026_FORBIDDEN_WORK_CASES = linkedMapOf(
    "Scanning arbitrary sockets" to
        "Only one compatible exact-root endpoint yields dispatch capability.",
    "First-match endpoint selection" to
        "Only one compatible exact-root endpoint yields dispatch capability.",
    "Ignoring capability set" to
        "Wrong root, build, schema, PID, runtime, capability, or unreachable endpoint is admitted.",
    "Revalidating raw strings downstream" to
        "Only one compatible exact-root endpoint yields dispatch capability.",
)
