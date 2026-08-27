package support.delivery

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test

@Serializable
private data class Kvp025TestEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val selectedSelectors: List<String>,
    val executedTestCount: Int,
    val observedFailureCount: Int,
    val misuse: Kvp025TestCaseDocument,
    val legalPath: Kvp025TestCaseDocument,
)

@Serializable
private data class Kvp025TestCaseDocument(
    val caseId: String,
    val name: String,
    val semanticOutcome: Kvp025SemanticOutcome,
    val testResult: Kvp025ObservedTestResult,
)

@Serializable internal enum class Kvp025SemanticOutcome { REJECTED, COMPLETE }
@Serializable internal enum class Kvp025ObservedTestResult { SUCCESS }

internal enum class Kvp025TestEvidenceFailure {
    TASK_MISMATCH,
    PROTOCOL_MISMATCH,
    SELECTOR_MISMATCH,
    CASE_MISMATCH,
    CASE_NOT_SUCCESSFUL,
    SUITE_MISMATCH,
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
}

internal class AdmittedKvp025TestEvidence internal constructor(
    val misuseName: String,
    val legalPathName: String,
    val executedTestCount: Int,
    val observedFailureCount: Int,
)

internal data class Kvp025ProofCaseExpectation(
    val misuseName: String,
    val legalPathName: String,
    val executedTestCount: Int,
    val observedFailureCount: Int,
)

internal sealed interface Kvp025ProofCaseExpectationAdmission {
    data class Complete(val expectation: Kvp025ProofCaseExpectation) :
        Kvp025ProofCaseExpectationAdmission
    data class Rejected(val failure: Kvp025TestEvidenceFailure) :
        Kvp025ProofCaseExpectationAdmission
}

internal sealed interface Kvp025TestEvidenceAdmission {
    data class Complete(val evidence: AdmittedKvp025TestEvidence) :
        Kvp025TestEvidenceAdmission
    data class Rejected(val failure: Kvp025TestEvidenceFailure) :
        Kvp025TestEvidenceAdmission
}

private data class Kvp025TestConfiguration(
    val misuse: ProofCommand,
    val legalPath: ProofCommand,
    val misuseSelector: String,
    val legalPathSelector: String,
)

private sealed interface Kvp025TestConfigurationAdmission {
    data class Complete(val configuration: Kvp025TestConfiguration) :
        Kvp025TestConfigurationAdmission
    data class Rejected(val failure: Kvp025TestEvidenceFailure) :
        Kvp025TestConfigurationAdmission
}

private sealed interface Kvp025SelectorRefinement {
    data class Complete(val selector: String) : Kvp025SelectorRefinement
    data object Rejected : Kvp025SelectorRefinement
}

private val kvp025TestEvidenceJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

@UntrackedTask(because = "Executes the graph-named KVP-025 misuse and legal paths")
abstract class Kvp025AtomicProofTestTask : Test() {
    @get:Input abstract val misuseCaseId: Property<String>
    @get:Input abstract val misuseCaseName: Property<String>
    @get:Input abstract val misuseSelector: Property<String>
    @get:Input abstract val legalPathCaseId: Property<String>
    @get:Input abstract val legalPathCaseName: Property<String>
    @get:Input abstract val legalPathSelector: Property<String>
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    init {
        doLast { writeCompleteEvidence() }
    }

    /** Configures selectors and named cases only from the canonical KVP-025 graph packet. */
    fun configureFrom(packet: TaskPacket) {
        val configuration = when (val admitted = packet.kvp025TestConfiguration()) {
            is Kvp025TestConfigurationAdmission.Complete -> admitted.configuration
            is Kvp025TestConfigurationAdmission.Rejected -> throw GradleException(
                "KVP-025 test configuration rejected: ${admitted.failure}",
            )
        }
        misuseCaseId.set(configuration.misuse.gateId)
        misuseCaseName.set(configuration.misuse.namedCase)
        misuseSelector.set(configuration.misuseSelector)
        legalPathCaseId.set(configuration.legalPath.gateId)
        legalPathCaseName.set(configuration.legalPath.namedCase)
        legalPathSelector.set(configuration.legalPathSelector)
        filter.includeTestsMatching(configuration.misuseSelector)
        filter.includeTestsMatching(configuration.legalPathSelector)
        filter.setFailOnNoMatchingTests(true)
        reports.junitXml.required.set(true)
    }

    private fun writeCompleteEvidence() {
        val expected = setOf(misuseCaseName.get(), legalPathCaseName.get())
        val reportBytes = Files.list(reports.junitXml.outputLocation.get().asFile.toPath()).use {
            paths -> paths.filter { it.fileName.toString().endsWith(".xml") }
                .map { path ->
                    when (val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)) {
                        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
                        is BoundaryFileRead.Rejected -> ""
                    }
                }
                .toList()
        }
        val missing = expected.filterNot { name ->
            val encoded = name.xmlAttributeValue()
            reportBytes.any { "name=\"$encoded()\"" in it }
        }.toSet()
        if (missing.isNotEmpty()) {
            throw GradleException("KVP-025 named cases did not complete successfully: $missing")
        }
        val executedTestCount = reportBytes.sumOf { report ->
            Regex("<testcase\\b").findAll(report).count()
        }
        val observedFailureCount = reportBytes.sumOf { report ->
            Regex("<(failure|error|skipped)\\b").findAll(report).count()
        }
        if (executedTestCount != KVP025_EXPECTED_TEST_COUNT || observedFailureCount != 0) {
            throw GradleException(
                "KVP-025 suite evidence rejected: tests=$executedTestCount, " +
                    "failures=$observedFailureCount",
            )
        }
        val document = Kvp025TestEvidenceDocument(
            schemaVersion = 1,
            taskId = KVP025_TASK_ID,
            selectedSelectors = listOf(misuseSelector.get(), legalPathSelector.get()).sorted(),
            executedTestCount = executedTestCount,
            observedFailureCount = observedFailureCount,
            misuse = Kvp025TestCaseDocument(
                misuseCaseId.get(),
                misuseCaseName.get(),
                Kvp025SemanticOutcome.REJECTED,
                Kvp025ObservedTestResult.SUCCESS,
            ),
            legalPath = Kvp025TestCaseDocument(
                legalPathCaseId.get(),
                legalPathCaseName.get(),
                Kvp025SemanticOutcome.COMPLETE,
                Kvp025ObservedTestResult.SUCCESS,
            ),
        )
        writeTextAtomically(evidenceFile.get().asFile.toPath(), encode(document))
    }
}

/**
 * Proof transition: test-evidence JSON plus canonical graph packet ->
 * `Kvp025TestEvidenceAdmission`.
 *
 * Establishes successful execution of the packet-named misuse and legal cases under exactly the
 * packet-derived selectors. Expected malformed or mismatched evidence is finite
 * [Kvp025TestEvidenceFailure]. Raw JSON exists only at the Gradle proof boundary.
 */
internal fun admitKvp025TestEvidence(
    raw: String,
    packet: TaskPacket,
): Kvp025TestEvidenceAdmission {
    val configuration = when (val admitted = packet.kvp025TestConfiguration()) {
        is Kvp025TestConfigurationAdmission.Complete -> admitted.configuration
        is Kvp025TestConfigurationAdmission.Rejected -> return rejected(admitted.failure)
    }
    val document = try {
        kvp025TestEvidenceJson.decodeFromString(Kvp025TestEvidenceDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(Kvp025TestEvidenceFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(Kvp025TestEvidenceFailure.MALFORMED_DOCUMENT)
    }
    val expected = expectedDocument(configuration)
    val failure = when {
        document.taskId != KVP025_TASK_ID -> Kvp025TestEvidenceFailure.TASK_MISMATCH
        document.selectedSelectors != expected.selectedSelectors ->
            Kvp025TestEvidenceFailure.SELECTOR_MISMATCH
        document.misuse != expected.misuse || document.legalPath != expected.legalPath ->
            Kvp025TestEvidenceFailure.CASE_MISMATCH
        document.misuse.testResult != Kvp025ObservedTestResult.SUCCESS ||
            document.legalPath.testResult != Kvp025ObservedTestResult.SUCCESS ->
            Kvp025TestEvidenceFailure.CASE_NOT_SUCCESSFUL
        document.executedTestCount != KVP025_EXPECTED_TEST_COUNT ||
            document.observedFailureCount != 0 -> Kvp025TestEvidenceFailure.SUITE_MISMATCH
        raw != encode(expected) -> Kvp025TestEvidenceFailure.NON_CANONICAL_DOCUMENT
        else -> null
    }
    return if (failure == null) {
        Kvp025TestEvidenceAdmission.Complete(
            AdmittedKvp025TestEvidence(
                document.misuse.name,
                document.legalPath.name,
                document.executedTestCount,
                document.observedFailureCount,
            ),
        )
    } else {
        rejected(failure)
    }
}

/**
 * Proof transition: canonical KVP-025 task packet -> `Kvp025ProofCaseExpectationAdmission`.
 *
 * Establishes the exact graph-selected cases and complete-suite cardinality that fresh evidence
 * must prove and a previously admitted report must preserve. It does not assert execution.
 */
internal fun expectedKvp025ProofCases(
    packet: TaskPacket,
): Kvp025ProofCaseExpectationAdmission = when (val admitted = packet.kvp025TestConfiguration()) {
    is Kvp025TestConfigurationAdmission.Complete -> {
        val expected = expectedDocument(admitted.configuration)
        Kvp025ProofCaseExpectationAdmission.Complete(
            Kvp025ProofCaseExpectation(
                expected.misuse.name,
                expected.legalPath.name,
                expected.executedTestCount,
                expected.observedFailureCount,
            ),
        )
    }
    is Kvp025TestConfigurationAdmission.Rejected ->
        Kvp025ProofCaseExpectationAdmission.Rejected(admitted.failure)
}

/** Preserves admitted test execution as the exact report-level case expectation. */
internal fun AdmittedKvp025TestEvidence.asCaseExpectation() = Kvp025ProofCaseExpectation(
    misuseName,
    legalPathName,
    executedTestCount,
    observedFailureCount,
)

private fun TaskPacket.kvp025TestConfiguration(): Kvp025TestConfigurationAdmission {
    if (task.id.value != KVP025_TASK_ID) {
        return Kvp025TestConfigurationAdmission.Rejected(
            Kvp025TestEvidenceFailure.TASK_MISMATCH,
        )
    }
    val misuseSelector = when (val refined = selector(proofCommand.misuse.command)) {
        is Kvp025SelectorRefinement.Complete -> refined.selector
        Kvp025SelectorRefinement.Rejected -> return rejectedConfiguration()
    }
    val legalPathSelector = when (val refined = selector(proofCommand.legalPath.command)) {
        is Kvp025SelectorRefinement.Complete -> refined.selector
        Kvp025SelectorRefinement.Rejected -> return rejectedConfiguration()
    }
    return Kvp025TestConfigurationAdmission.Complete(Kvp025TestConfiguration(
        proofCommand.misuse,
        proofCommand.legalPath,
        misuseSelector,
        legalPathSelector,
    ))
}

private fun selector(command: String): Kvp025SelectorRefinement {
    val match = Regex("--tests \\\"([^\\\"]+)\\\"").find(command)
        ?: return Kvp025SelectorRefinement.Rejected
    return if (match.range.last == command.lastIndex) {
        Kvp025SelectorRefinement.Complete(match.groupValues[1])
    } else {
        Kvp025SelectorRefinement.Rejected
    }
}

private fun rejectedConfiguration() = Kvp025TestConfigurationAdmission.Rejected(
    Kvp025TestEvidenceFailure.PROTOCOL_MISMATCH,
)

private fun expectedDocument(configuration: Kvp025TestConfiguration) =
    Kvp025TestEvidenceDocument(
        1,
        KVP025_TASK_ID,
        listOf(configuration.misuseSelector, configuration.legalPathSelector).sorted(),
        KVP025_EXPECTED_TEST_COUNT,
        0,
        Kvp025TestCaseDocument(
            configuration.misuse.gateId,
            configuration.misuse.namedCase,
            Kvp025SemanticOutcome.REJECTED,
            Kvp025ObservedTestResult.SUCCESS,
        ),
        Kvp025TestCaseDocument(
            configuration.legalPath.gateId,
            configuration.legalPath.namedCase,
            Kvp025SemanticOutcome.COMPLETE,
            Kvp025ObservedTestResult.SUCCESS,
        ),
    )

private fun encode(document: Kvp025TestEvidenceDocument) =
    kvp025TestEvidenceJson.encodeToString(Kvp025TestEvidenceDocument.serializer(), document) + "\n"

private fun rejected(failure: Kvp025TestEvidenceFailure) =
    Kvp025TestEvidenceAdmission.Rejected(failure)

private fun String.xmlAttributeValue() = replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private const val KVP025_TASK_ID = "KVP-025"
private const val KVP025_EXPECTED_TEST_COUNT = 9
