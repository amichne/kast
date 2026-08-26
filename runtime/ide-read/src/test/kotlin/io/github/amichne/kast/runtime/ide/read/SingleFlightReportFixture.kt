package io.github.amichne.kast.runtime.ide.read

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

@Serializable
private data class Kvp020TestReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: String,
    val publicInterface: String,
    val effect: String,
    val scopeEvidence: List<String>,
    val states: List<String>,
    val transitions: List<String>,
    val activePermitLimit: Int,
    val queuedRequestLimit: Int,
    val admissionCases: List<String>,
    val retirementCauses: List<String>,
    val cancellationCauses: List<String>,
    val terminalizationLimitPerAuthority: Int,
    val promotionLimitPerActiveTerminalization: Int,
    val freshnessObservationCount: Int,
    val semanticExecutionCount: Int,
    val retainedCapabilityEvidence: List<String>,
    val forbiddenWork: List<Kvp020TestCount>,
    val forbiddenRetention: List<Kvp020TestCount>,
    val predecessorReceipts: List<Kvp020TestPredecessor>,
)

@Serializable private data class Kvp020TestCount(val kind: String, val observedCount: Int)
@Serializable private data class Kvp020TestPredecessor(val receiptId: String, val sha256: String)

@Serializable
private data class Kvp020TestReceiptDocument(
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

internal fun assertExactSingleFlightReport() {
    val raw = Files.readString(requiredKvp020Path("kast.ide.single.flight.report"))
    val report = KVP020_TEST_JSON.decodeFromString(Kvp020TestReportDocument.serializer(), raw)
    assertEquals(KVP020_TEST_JSON.encodeToString(report) + "\n", raw)
    assertEquals(1, report.schemaVersion)
    assertEquals("KVP-020", report.taskId)
    assertEquals("READ_RUNTIME", report.authority)
    assertEquals("ProjectReadPermit", report.publicInterface)
    assertEquals("PURE_STATE_TRANSITIONS", report.effect)
    assertEquals(
        listOf("CANONICAL_ROOT", "PROJECT_READ_EPOCH_COMPARISON_DOMAIN"),
        report.scopeEvidence,
    )
    assertEquals(listOf("IDLE", "ACTIVE", "ACTIVE_AND_QUEUED", "RETIRED"), report.states)
    assertEquals(observeKvp020SingleFlightTransitions(), report.transitions)
    assertEquals(1, report.activePermitLimit)
    assertEquals(1, report.queuedRequestLimit)
    assertEquals(listOf(
        "ACTIVE_PERMIT_ISSUED",
        "REQUEST_QUEUED",
        "REJECTED_BUSY",
        "REJECTED_PROJECT_SCOPE",
        "REJECTED_RETIRED",
    ), report.admissionCases)
    assertEquals(listOf(
        "PROJECT_DISPOSED",
        "PLUGIN_UNLOADED",
        "ENDPOINT_PUBLICATION_FAILED",
        "SOCKET_FAILED",
    ), report.retirementCauses)
    assertEquals(listOf("REQUEST_CANCELLED", "CLIENT_DISCONNECTED"), report.cancellationCauses)
    assertEquals(1, report.terminalizationLimitPerAuthority)
    assertEquals(1, report.promotionLimitPerActiveTerminalization)
    assertEquals(0, report.freshnessObservationCount)
    assertEquals(0, report.semanticExecutionCount)
    assertEquals(listOf(
        "VFS_PASSIVE_READ_CAPABILITY",
        "CANONICAL_ROOT",
        "ADMITTED_PROJECT_READ_EPOCH",
    ), report.retainedCapabilityEvidence)
    assertZeroInventory(report.forbiddenWork, listOf(
        "UNBOUNDED_CHANNEL",
        "GLOBAL_LOCK_ACROSS_PROJECTS",
        "HOLDING_PERMIT_AFTER_DISCONNECT_OR_DISPOSAL",
        "PARALLEL_SEMANTIC_READS_BY_DEFAULT",
    ))
    assertZeroInventory(report.forbiddenRetention, listOf(
        "INTELLIJ_PROJECT",
        "CALLBACK",
        "EXECUTION_EFFECT",
        "GLOBAL_REGISTRY",
        "CROSS_PROJECT_LOCK",
        "CHANNEL",
        "UNBOUNDED_COLLECTION",
    ))

    val project = expectedKvp020Predecessor(
        "kast.ide.single.flight.kvp014.receipt",
        "KVP-014-COMPLETE",
        "KVP-014",
    )
    val freshness = expectedKvp020Predecessor(
        "kast.ide.single.flight.kvp019.receipt",
        "KVP-019-COMPLETE",
        "KVP-019",
    )
    val expectedHead = requiredKvp020Property("kast.ide.single.flight.expected.head")
    assertEquals(expectedHead, project.second.exactHead)
    assertEquals(expectedHead, freshness.second.exactHead)
    assertEquals(listOf(project.first, freshness.first), report.predecessorReceipts)
}

private fun assertZeroInventory(actual: List<Kvp020TestCount>, expected: List<String>) {
    assertEquals(expected, actual.map(Kvp020TestCount::kind))
    assertTrue(actual.all { it.observedCount == 0 })
}

private fun expectedKvp020Predecessor(
    property: String,
    receiptId: String,
    taskId: String,
): Pair<Kvp020TestPredecessor, Kvp020TestReceiptDocument> {
    val receipt = KVP020_TEST_JSON.decodeFromString(
        Kvp020TestReceiptDocument.serializer(),
        Files.readString(requiredKvp020Path(property)),
    )
    assertEquals(receiptId, receipt.receiptId)
    assertEquals(taskId, receipt.taskId)
    assertEquals("$taskId-COMPLETE-GATE", receipt.gateId)
    assertTrue(receipt.receiptDigest.matches(Regex("[0-9a-f]{64}")))
    return Kvp020TestPredecessor(receiptId, receipt.receiptDigest) to receipt
}

private fun requiredKvp020Path(property: String): Path {
    return Path.of(requiredKvp020Property(property))
}

private fun requiredKvp020Property(property: String): String =
    requireNotNull(System.getProperty(property)) { "missing $property" }

private val KVP020_TEST_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "    "
}
