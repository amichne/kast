package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.DiagnosticDocument
import io.github.amichne.kast.protocol.contract.DiagnosticKnownCountDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLimitationReasonDocument
import io.github.amichne.kast.protocol.contract.DiagnosticLocationDocument
import io.github.amichne.kast.protocol.contract.DiagnosticRangeDocument
import io.github.amichne.kast.protocol.contract.DiagnosticSeverityDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.changeRecoverCliProjector
import io.github.amichne.kast.protocol.wire.presentation.diagnosticCheckCliProjector
import io.github.amichne.kast.protocol.wire.presentation.queryRunCliProjector
import io.github.amichne.kast.protocol.wire.presentation.symbolDiscoverCliProjector
import io.github.amichne.kast.protocol.wire.presentation.symbolInspectCliProjector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedCliProjectionTest {
    @Test
    fun `completed empty query reports exhausted scope`() {
        val result = QueryRunResult(bounded(emptyList()), bounded(emptyList()))
        val outcome = OperationOutcome.Complete(evidence(CanonicalOperation.QUERY_RUN, result))
        val projected = queryRunCliProjector.project(outcome) as ProjectedOperationOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        assertTrue(document.getValue("items").jsonArray.isEmpty())
        assertTrue(document.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.boolean)
    }

    @Test
    fun `symbol ref output is derived from the same exact token without changing overload identity`() {
        val tokens = listOf("exact:v3:first-overload", "exact:v3:second-overload")
        val result =
            QueryRunResult(
                bounded(
                    tokens.map { token ->
                        QueryResultItemDocument.ExactSymbol(
                            QueryReferenceDocument.ExactSymbol(text(token)),
                            SymbolKindDocument.FUNCTION,
                            text("overloaded"),
                            null,
                            null,
                            bounded(emptyList()),
                            (io.github.amichne.kast.protocol.contract.SymbolIdDocument.parse("sym:" + "A".repeat(43))
                                    as Refinement.Refined)
                                .value,
                        )
                    }
                ),
                bounded(emptyList()),
            )
        val projected =
            queryRunCliProjector.project(OperationOutcome.Complete(evidence(CanonicalOperation.QUERY_RUN, result)))
                as ProjectedOperationOutcome.Complete
        val items = Json.parseToJsonElement(projected.document.value).jsonObject.getValue("items").jsonArray
        assertEquals(2, items.size)
        items.zip(tokens).forEach { (item, token) ->
            assertEquals(kotlinx.serialization.json.JsonPrimitive(token), item.jsonObject.getValue("ref"))
            assertTrue("symbol_ref" !in item.jsonObject)
            assertTrue("symbol_id" !in item.jsonObject)
        }
    }

    @Test
    fun `generated discovery serializer preserves every closed item variant`() {
        val result =
            SymbolDiscoverResult(
                bounded(
                    listOf(
                        SymbolDiscoveryDocument.File(
                            text("candidate:file"),
                            text("A.kt"),
                            text("src/A.kt"),
                        ),
                        SymbolDiscoveryDocument.Declaration(
                            candidateSelector = text("candidate:A"),
                            kind = SymbolDiscoveryKindDocument.CLASS,
                            name = text("A"),
                            file = text("src/A.kt"),
                            offset = offset(3),
                        ),
                        SymbolDiscoveryDocument.TextMatch(
                            candidateSelector = text("candidate:range"),
                            query = text("TODO"),
                            file = text("src/A.kt"),
                            range = range(4, 8),
                        ),
                    )
                )
            )

        val projected =
            symbolDiscoverCliProjector.project(
                OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_DISCOVER, result))
            ) as ProjectedOperationOutcome.Complete

        assertEquals(
            "{\"operation\":\"symbol.discover\",\"status\":\"complete\",\"items\":[" +
                "{\"type\":\"file\",\"candidateSelector\":\"candidate:file\"," +
                "\"name\":\"A.kt\",\"file\":\"src/A.kt\"}," +
                "{\"type\":\"declaration\",\"candidateSelector\":\"candidate:A\"," +
                "\"kind\":\"class\",\"name\":\"A\",\"file\":\"src/A.kt\",\"offset\":3}," +
                "{\"type\":\"text-match\",\"candidateSelector\":\"candidate:range\"," +
                "\"query\":\"TODO\",\"file\":\"src/A.kt\"," +
                "\"range\":{\"startInclusive\":4,\"endExclusive\":8}}]}",
            projected.document.value,
        )
    }

    @Test
    fun `generated symbol serializer preserves coherent compiler evidence`() {
        val signature = CompilerSignatureDocument.ClassLike(text("A"))
        val compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
        val result =
            SymbolInspectResult(
                SymbolDocument.create(
                        selector = text("exact:A"),
                        kind = SymbolKindDocument.CLASSLIKE,
                        name = text("A"),
                        qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(text("A")),
                        file = text("src/A.kt"),
                        range = range(0, 7),
                        compilerEvidence = compilerEvidence,
                    )
                    .refined()
            )

        val projected =
            symbolInspectCliProjector.project(
                OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_INSPECT, result))
            ) as ProjectedOperationOutcome.Complete

        assertEquals(
            io.github.amichne.kast.cli.SymbolInspectionFixture.expectedClass(compilerEvidence.identity.value),
            projected.document.value,
        )
    }

    @Test
    fun `generated symbol serializer retains extension property receiver proof`() {
        val signature =
            CompilerSignatureDocument.Property(
                qualifiedIdentity = text("sample.tag"),
                receiver = CompilerReceiverDocument.Present(text("kotlin.String")),
                contextReceivers = BoundedProtocolList.create(listOf(text("sample.Context"))).refined(),
                returnType = text("kotlin.Int"),
            )
        val compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
        val result =
            SymbolInspectResult(
                SymbolDocument.create(
                        selector = text("exact:tag"),
                        kind = SymbolKindDocument.PROPERTY,
                        name = text("tag"),
                        qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(text("sample.tag")),
                        file = text("src/Extensions.kt"),
                        range = range(0, 12),
                        compilerEvidence = compilerEvidence,
                    )
                    .refined()
            )

        val projected =
            symbolInspectCliProjector.project(
                OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_INSPECT, result))
            ) as ProjectedOperationOutcome.Complete

        assertTrue(
            projected.document.value.contains("\"receiver\":{\"type\":\"present\",\"compilerType\":\"kotlin.String\"}")
        )
        assertTrue(projected.document.value.contains("\"contextReceivers\":[\"sample.Context\"]"))
    }

    @Test
    @Suppress("LongMethod")
    fun `generated qualified documents append qualification after payload`() {
        val diagnosticsProjected =
            diagnosticCheckCliProjector.project(
                OperationOutcome.Qualified(
                    evidence(
                        CanonicalOperation.DIAGNOSTIC_CHECK,
                        DiagnosticCheckResult(
                            bounded(
                                listOf(
                                    DiagnosticDocument(
                                        severity = DiagnosticSeverityDocument.WARNING,
                                        code = text("UNUSED_SYMBOL"),
                                        message = text("warning"),
                                        location =
                                            DiagnosticLocationDocument(
                                                candidateSelector = text("candidate:diagnostic"),
                                                file = text("src/A.kt"),
                                                range =
                                                    DiagnosticRangeDocument.create(
                                                            offset(4),
                                                            offset(4),
                                                        )
                                                        .refined(),
                                            ),
                                    )
                                )
                            )
                        ),
                    ),
                    diagnosticCoverageQualification(),
                )
            ) as ProjectedOperationOutcome.Qualified

        val diagnosticsDocument = Json.parseToJsonElement(diagnosticsProjected.document.value).jsonObject
        assertEquals("diagnostic.check", diagnosticsDocument.getValue("operation").jsonPrimitive.content)
        assertEquals("qualified", diagnosticsDocument.getValue("status").jsonPrimitive.content)
        assertEquals("IDE_FILE_DIAGNOSTICS", diagnosticsDocument.getValue("analysisKind").jsonPrimitive.content)
        assertEquals(1, diagnosticsDocument.getValue("diagnostics").jsonArray.size)
        assertEquals(
            "indexing",
            diagnosticsDocument
                .getValue("qualification")
                .jsonObject
                .getValue("limitations")
                .jsonArray
                .single()
                .jsonObject
                .getValue("reason")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `qualified diagnostic output retains structured proof`() {
        val diagnostics =
            diagnosticCheckCliProjector.project(
                OperationOutcome.Qualified(
                    evidence(CanonicalOperation.DIAGNOSTIC_CHECK, DiagnosticCheckResult(bounded(emptyList()))),
                    diagnosticResultLimitQualification(),
                )
            ) as ProjectedOperationOutcome.Qualified
        val qualification = diagnostics.qualification().jsonObject
        assertEquals(
            setOf("knownDiagnosticCount", "resultLimitReached", "analyzedFiles", "limitations"),
            qualification.keys,
        )
        assertEquals("0", qualification.getValue("knownDiagnosticCount").jsonPrimitive.content)
        assertEquals("true", qualification.getValue("resultLimitReached").jsonPrimitive.content)
        assertEquals(
            listOf("src/A.kt"),
            qualification.getValue("analyzedFiles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(qualification.getValue("limitations").jsonArray.isEmpty())
    }

    @Test
    fun `generated rejection serializer retains operation-specific reason`() {
        val projected =
            changeRecoverCliProjector.project(OperationOutcome.Rejected(ChangeRecoverRejection.JOURNAL_UNAVAILABLE))
                as ProjectedOperationOutcome.Rejected

        assertEquals(
            "{\"operation\":\"change.recover\",\"status\":\"rejected\"," + "\"reason\":\"journal-unavailable\"}",
            projected.document.value,
        )
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value> evidence(
        operation: CanonicalOperation,
        value: Value,
    ): EvidenceEnvelope<Value> =
        EvidenceEnvelope(
            operation.id,
            EvidenceGeneration.parse(1).refined(),
            value,
        )

    private fun diagnosticCoverageQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
                DiagnosticKnownCountDocument.parse(1).refined(),
                resultLimitReached = false,
                analyzedFiles = emptyList(),
                limitations =
                    listOf(
                        DiagnosticLimitationDocument(
                            text("src/A.kt"),
                            DiagnosticLimitationReasonDocument.INDEXING,
                        )
                    ),
            )
            .refined()

    private fun diagnosticResultLimitQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
                DiagnosticKnownCountDocument.parse(0).refined(),
                resultLimitReached = true,
                analyzedFiles = listOf(text("src/A.kt")),
                limitations = emptyList(),
            )
            .refined()

    private fun ProjectedOperationOutcome.Qualified.qualification() =
        Json.parseToJsonElement(document.value).jsonObject.getValue("qualification")

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
