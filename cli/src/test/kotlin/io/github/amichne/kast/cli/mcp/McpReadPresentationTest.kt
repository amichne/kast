package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.DiagnosticCoverageCliDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpReadPresentationTest {
    @Test
    fun `exhaustive empty search remains complete with its live basis`() {
        val document = queryDocument(items = emptyList())
        val presented = mcpReadPresentation("search_classes", CliExit.Complete(document))
        assertNotNull(presented)
        val envelope = requireNotNull(presented).envelope.jsonObject
        assertTrue(McpStructuredResults.validates("search_classes", envelope))
        assertEquals("complete", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("true", envelope.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.content)
        assertEquals(
            "SAVED_PSI_COMMITTED",
            envelope.getValue("basis").jsonObject.getValue("contentView").jsonPrimitive.content,
        )
        assertEquals(Json.parseToJsonElement(document.value), envelope.getValue("data"))
        assertEquals("0 results; requested scope exhausted", presented.summary)
    }

    @Test
    fun `a returned reference survives the structured envelope unchanged`() {
        val ref = "exact:v5:OpaqueReturnedReference"
        val document = queryDocument(items = listOf(TestQueryItem(ref = ref, name = "Registry")))
        val presented = requireNotNull(mcpReadPresentation("search_classes", CliExit.Complete(document)))
        val data = presented.envelope.jsonObject.getValue("data").jsonObject
        assertEquals(ref, data.getValue("items").jsonArray.single().jsonObject.getValue("ref").jsonPrimitive.content)
        assertTrue(presented.summary.startsWith("Registry — class\nsrc/Registry.kt @ offset 28"))
    }

    @Test
    fun `budget limited diagnostic scan is partial even when empty`() {
        val document =
            CanonicalJsonDocument.generated(TestPartialDiagnostics.serializer()).create(TestPartialDiagnostics())
        val presented = requireNotNull(mcpReadPresentation("check_diagnostics", CliExit.Qualified(document)))
        val envelope = presented.envelope.jsonObject
        assertTrue(McpStructuredResults.validates("check_diagnostics", envelope))
        assertEquals("partial", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("budget", envelope.getValue("stopReason").jsonPrimitive.content)
        assertEquals("false", envelope.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.content)
        assertTrue(presented.summary.contains("absence unverified"))
    }

    @Test
    fun `host request rejection has a typed code and original evidence`() {
        val document = CanonicalJsonDocument.generated(TestHostRejection.serializer()).create(TestHostRejection())
        val presented = requireNotNull(mcpReadPresentation("source_read", CliExit.OperationRejected(document)))
        val envelope = presented.envelope.jsonObject
        assertTrue(McpStructuredResults.validates("source_read", envelope))
        assertEquals("rejected", envelope.getValue("status").jsonPrimitive.content)
        val error = envelope.getValue("error").jsonObject
        assertEquals("INVALID_REQUEST", error.getValue("code").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(document.value), error.getValue("evidence"))
    }

    @Test
    fun `expanded returned source retains its selected region and completeness`() {
        val document =
            CanonicalJsonDocument.generated(TestExpandedSourceDocument.serializer())
                .create(TestExpandedSourceDocument(text = TestExpandedText.Returned()))
        val envelope =
            requireNotNull(mcpReadPresentation("source_read", CliExit.Complete(document))).envelope.jsonObject
        val coverage = envelope.getValue("coverage").jsonObject
        assertEquals("complete", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("true", coverage.getValue("exhaustive").jsonPrimitive.content)
        assertEquals("false", coverage.getValue("textTruncated").jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(document.value).jsonObject.getValue("region"),
            coverage.getValue("selectedRegion"),
        )
    }

    @Test
    fun `expanded withheld source is partial even when canonical status is complete`() {
        val document =
            CanonicalJsonDocument.generated(TestExpandedSourceDocument.serializer())
                .create(TestExpandedSourceDocument(text = TestExpandedText.Withheld()))
        val envelope =
            requireNotNull(mcpReadPresentation("source_read", CliExit.Complete(document))).envelope.jsonObject
        val coverage = envelope.getValue("coverage").jsonObject
        assertEquals("partial", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("false", coverage.getValue("exhaustive").jsonPrimitive.content)
        assertEquals("true", coverage.getValue("textTruncated").jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(document.value).jsonObject.getValue("region"),
            coverage.getValue("selectedRegion"),
        )
    }
}

private fun queryDocument(items: List<TestQueryItem>): CanonicalJsonDocument =
    CanonicalJsonDocument.generated(TestQueryDocument.serializer()).create(TestQueryDocument(items = items))

@Serializable
private data class TestQueryDocument(
    val operation: String = "query.run",
    val status: String = "complete",
    val items: List<TestQueryItem>,
    val failures: List<String> = emptyList(),
    val coverage: TestCoverage = TestCoverage(),
    val live: TestLive = TestLive(),
)

@Serializable
private data class TestQueryItem(
    val type: String = "exact-symbol",
    val ref: String,
    val kind: String = "classlike",
    val name: String,
    val location: TestQueryLocation = TestQueryLocation(),
    val signature: TestQuerySignature = TestQuerySignature(),
)

@Serializable
private data class TestQueryLocation(val file: String = "/workspace/src/Registry.kt", val offset: Int = 28)

@Serializable private data class TestQuerySignature(val qualifiedIdentity: String = "sample.Registry")

@Serializable private data class TestCoverage(val exhaustive: Boolean = true)

@Serializable
private data class TestLive(
    val root: String = "/workspace",
    val host: String = "host-1",
    val epoch: Long = 1,
    val contentView: String = "SAVED_PSI_COMMITTED",
    val version: Int = 1,
)

@Serializable
private data class TestPartialDiagnostics(
    val operation: String = "diagnostic.check",
    val status: String = "qualified",
    val diagnostics: List<String> = emptyList(),
    val coverage: DiagnosticCoverageCliDocument = DiagnosticCoverageCliDocument("src/Registry.kt", 1, 0, null, false),
    val qualification: TestDiagnosticQualification = TestDiagnosticQualification(),
    val progress: TestDiagnosticProgress = TestDiagnosticProgress(),
    val live: TestLive = TestLive(),
)

@Serializable private data class TestDiagnosticQualification(val resultLimitReached: Boolean = false)

@Serializable private data class TestDiagnosticProgress(val stop: String = "analysis_pending")

@Serializable
private data class TestHostRejection(val failure: String = "INVALID_REQUEST", val type: String = "HOST_REJECTED")

@Serializable
private data class TestExpandedSourceDocument(
    val operation: String = "source.read",
    val status: String = "complete",
    val region: TestExpandedRegion = TestExpandedRegion(),
    val text: TestExpandedText,
    val live: TestLive = TestLive(),
)

@Serializable
private data class TestExpandedRegion(
    val kind: String = "declaration",
    val selection: TestExpandedSelection = TestExpandedSelection(),
)

@Serializable
private data class TestExpandedSelection(
    val selector: String = "exact:v5:Registry",
    val range: TestExpandedRange = TestExpandedRange(),
)

@Serializable private data class TestExpandedRange(val startInclusive: Int = 0, val endExclusive: Int = 8)

@Serializable
private sealed interface TestExpandedText {
    @Serializable
    @SerialName("returned")
    data class Returned(
        val selection: TestExpandedSelection = TestExpandedSelection(),
        val text: String = "class R",
        val lines: TestExpandedLines = TestExpandedLines(),
    ) : TestExpandedText

    @Serializable @SerialName("withheld") data class Withheld(val reason: String = "byte-limit") : TestExpandedText
}

@Serializable private data class TestExpandedLines(val startInclusive: Long = 1, val endInclusive: Long = 1)
