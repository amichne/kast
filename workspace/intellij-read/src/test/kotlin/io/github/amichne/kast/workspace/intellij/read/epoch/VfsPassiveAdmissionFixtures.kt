package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

@Serializable
private data class Kvp019TestReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: String,
    val publicInterface: String,
    val admissionMode: String,
    val admissionCases: List<String>,
    val freshnessObservationCountPerAdmission: Int,
    val retainedCapabilityEvidence: List<String>,
    val unavailableObservationFailures: List<String>,
    val unavailableObservationFailureCount: Int,
    val observationFailureStages: List<String>,
    val forbiddenWork: List<Kvp019TestForbiddenWork>,
    val predecessorReceipts: List<Kvp019TestPredecessor>,
)

@Serializable
private data class Kvp019TestForbiddenWork(val kind: String, val observedCount: Int)

@Serializable
private data class Kvp019TestPredecessor(val receiptId: String, val sha256: String)

@Serializable
private data class Kvp019TestReceiptDocument(
    val schemaVersion: Int,
    val receiptId: String,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val taskId: String,
    val gateId: String,
    val dependencyReceiptDigests: Map<String, String>,
    val declaredInputDigest: String,
    val commandDigest: String,
    val observedProofValues: Map<String, String>,
    val artifactDigests: Map<String, String>,
    val recordedAtUtc: String,
    val receiptDigest: String,
)

internal class RecordingFreshnessEpochSource(
    var observation: () -> Refinement<Int, ProjectReadEpochObservationFailure> = {
        Refinement.Refined(1)
    },
) {
    var observationCount: Int = 0
        private set

    val source: ProjectReadEpoch.Source<Int> = ProjectReadEpoch.Source.create {
        observationCount += 1
        observation()
    }

    fun observeEpoch(): ProjectReadEpoch<*> = when (val observed = source.observe()) {
        is ProjectReadEpochObservation.Observed -> observed.epoch
        is ProjectReadEpochObservation.Rejected -> error("unexpected ${observed.failure}")
    }
}

internal fun admittedFreshnessProject(
    source: RecordingFreshnessEpochSource,
): AdmittedIdeProject = when (val result = AdmittedIdeProject.admitObserved(
    opaqueProject(),
    FIXTURE_ROOT,
    FIXTURE_COMPATIBILITY,
    FIXTURE_COMPATIBILITY_POLICY,
    RecordingProjectObservation(),
    ExistingProjectReadEpochSourceFactory { _, _ -> Refinement.Refined(source.source) },
)) {
    is ExistingProjectAdmission.Admitted -> result.project
    is ExistingProjectAdmission.Rejected -> error("unexpected ${result.failure}")
}

internal fun admittedFreshnessCapability(
    admission: VfsPassiveReadAdmission,
): VfsPassiveReadCapability = when (admission) {
    is VfsPassiveReadAdmission.Admitted -> admission.capability
    is VfsPassiveReadAdmission.Rejected -> error("unexpected ${admission.failure}")
}

internal fun rejectedFreshnessFailure(
    admission: VfsPassiveReadAdmission,
): VfsPassiveReadAdmissionFailure = when (admission) {
    is VfsPassiveReadAdmission.Admitted -> error("unexpected admission")
    is VfsPassiveReadAdmission.Rejected -> admission.failure
}

internal fun assertExactVfsPassiveReport() {
    val reportPath = requiredReportPath("kast.ide.vfs.passive.report")
    assumeTrue(
        Files.isRegularFile(reportPath),
        "KVP-019 report closure is exercised by its generated-proof lifecycle",
    )
    val raw = Files.readString(reportPath)
    val report = KVP019_TEST_JSON.decodeFromString(Kvp019TestReportDocument.serializer(), raw)
    assertEquals(KVP019_TEST_JSON.encodeToString(report) + "\n", raw)
    assertEquals(1, report.schemaVersion)
    assertEquals("KVP-019", report.taskId)
    assertEquals("READ_EPOCH", report.authority)
    assertEquals("VfsPassiveReadCapability", report.publicInterface)
    assertEquals("IDE_SNAPSHOT_ONLY", report.admissionMode)
    assertEquals(listOf(
        "ADMITTED_SAME_SOURCE_EQUAL_STATE",
        "REJECTED_MOVED_STATE",
        "REJECTED_INCOMPARABLE_SOURCE",
        "REJECTED_PROJECT_DISPOSED",
        "REJECTED_DUMB_MODE",
        "REJECTED_UNAVAILABLE_OBSERVATION",
        "PROPAGATED_PLATFORM_CANCELLATION",
    ), report.admissionCases)
    assertEquals(1, report.freshnessObservationCountPerAdmission)
    assertEquals(listOf("CANONICAL_ROOT", "ADMITTED_EPOCH"), report.retainedCapabilityEvidence)
    assertEquals(listOf(
        "WRONG_THREAD",
        "PROJECT_NOT_OPEN",
        "PROJECT_NOT_INITIALIZED",
        "PROJECT_ROOT_UNAVAILABLE",
        "PROJECT_ROOT_MALFORMED",
        "GRADLE_MODEL_UNAVAILABLE",
        "GRADLE_MODEL_INCOMPLETE",
        "GRADLE_MODEL_AMBIGUOUS",
        "GRADLE_ROOT_UNAVAILABLE",
        "GRADLE_ROOT_MALFORMED",
        "IMPORT_TIMESTAMPS_INCOHERENT",
        "VFS_BATCH_LIMIT_EXCEEDED",
        "VFS_PATH_MALFORMED",
        "SIGNAL_EXHAUSTED",
        "READ_PREEMPTED",
        "OBSERVATION_FAILED",
    ), report.unavailableObservationFailures)
    assertEquals(report.unavailableObservationFailures.size,
        report.unavailableObservationFailureCount)
    assertEquals(listOf(
        "THREAD",
        "DISPOSAL",
        "OPEN",
        "INITIALIZATION",
        "PROJECT_ROOT",
        "PROJECT_MODEL",
        "PSI",
        "VFS",
        "ROOT_MODEL",
        "DUMB_MODE",
    ), report.observationFailureStages)
    assertEquals(listOf(
        "VFS_REFRESH",
        "GRADLE_IMPORT",
        "BACKGROUND_REPAIR",
        "PER_EVENT_SEMANTIC_JOB",
        "EVENT_TRIGGERED_SEMANTIC_WORK",
    ), report.forbiddenWork.map(Kvp019TestForbiddenWork::kind))
    assertTrue(report.forbiddenWork.all { it.observedCount == 0 })

    val readEpoch = expectedPredecessor(
        "kast.ide.vfs.passive.kvp017.receipt",
        "KVP-017-COMPLETE",
        "KVP-017",
    )
    val hosted = expectedPredecessor(
        "kast.ide.vfs.passive.kvp018.receipt",
        "KVP-018-COMPLETE",
        "KVP-018",
    )
    assertEquals(readEpoch.second.exactHead, hosted.second.exactHead)
    assertEquals(listOf(readEpoch.first, hosted.first), report.predecessorReceipts)
}

private fun expectedPredecessor(
    property: String,
    receiptId: String,
    taskId: String,
): Pair<Kvp019TestPredecessor, Kvp019TestReceiptDocument> {
    val receipt = KVP019_TEST_JSON.decodeFromString(
        Kvp019TestReceiptDocument.serializer(),
        Files.readString(requiredReportPath(property)),
    )
    assertEquals(receiptId, receipt.receiptId)
    assertEquals(taskId, receipt.taskId)
    assertEquals("$taskId-COMPLETE-GATE", receipt.gateId)
    assertTrue(receipt.receiptDigest.matches(Regex("[0-9a-f]{64}")))
    return Kvp019TestPredecessor(receiptId, receipt.receiptDigest) to receipt
}

private fun requiredReportPath(property: String): Path {
    val raw = requireNotNull(System.getProperty(property)) { "missing $property" }
    return Path.of(raw)
}

private val KVP019_TEST_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
