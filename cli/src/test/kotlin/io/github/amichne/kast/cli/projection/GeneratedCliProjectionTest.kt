package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
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
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class GeneratedCliProjectionTest {
    @Test
    fun `traversal graph normalizes repeated nodes and retains compact proof references`() {
        val source = symbol("exact:A", "A", "src/A.kt")
        val target = symbol("exact:B", "B", "src/B.kt")
        val fact = RelationFactDocument(
            meaning = RelationKindDocument.CALLERS,
            source = source,
            target = target,
            occurrence = RelationOccurrenceDocument(
                text("candidate:occurrence"),
                text("src/B.kt"),
                range(4, 8),
            ),
            provenance = RelationProvenanceDocument.K2_AUTHORED_SOURCE,
            coverage = RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
        )

        val records = listOf(
            TraversalRecordDocument(depth(1), fact),
            TraversalRecordDocument(depth(2), fact),
        )
        val graph = normalizeTraversalGraph(
            text("/workspace"),
            EvidenceGeneration.parse(1).refined(),
            records,
        )

        assertEquals(2, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        assertEquals(2, graph.proofs.size)
        assertEquals(listOf(0, 0), graph.edges.map { it.source.value })
        assertEquals(listOf(1, 1), graph.edges.map { it.target.value })
        assertEquals(
            source.compilerEvidence.identity.value,
            graph.proofs[graph.nodes.first().proof.value].identity,
        )
        val projected = traversalRunCliProjector.project(
            OperationOutcome.Complete(
                evidence(
                    CanonicalOperation.TRAVERSAL_RUN,
                    TraversalRunResult(text("/workspace"), bounded(records)),
                ),
            ),
        ) as ProjectedCliOutcome.Complete
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        val projectedGraph = document.getValue("graph").jsonObject
        val snapshot = projectedGraph.getValue("snapshot").jsonObject
        assertTrue("records" !in document)
        assertEquals("/workspace", snapshot.getValue("canonicalRoot").toString().trim('"'))
        assertEquals("1", snapshot.getValue("generation").toString())
        assertEquals(2, projectedGraph.getValue("nodes").jsonArray.size)
        assertEquals(2, projectedGraph.getValue("edges").jsonArray.size)
        assertEquals(2, projectedGraph.getValue("proofs").jsonArray.size)
        assertTrue(
            projectedGraph.getValue("nodes").jsonArray
                .none { "compilerEvidence" in it.jsonObject },
        )
    }

    @Test
    fun `generated discovery serializer preserves every closed item variant`() {
        val result = SymbolDiscoverResult(
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
                ),
            ),
        )

        val projected = symbolDiscoverCliProjector.project(
            OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_DISCOVER, result)),
        ) as ProjectedCliOutcome.Complete

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
        val result = SymbolDescribeResult(
            SymbolDocument.create(
                selector = text("exact:A"),
                kind = SymbolKindDocument.CLASSLIKE,
                name = text("A"),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(text("A")),
                file = text("src/A.kt"),
                range = range(0, 7),
                compilerEvidence = compilerEvidence,
            ).refined(),
        )

        val projected = symbolDescribeCliProjector.project(
            OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_DESCRIBE, result)),
        ) as ProjectedCliOutcome.Complete

        assertEquals(
                "{\"operation\":\"symbol.describe\",\"status\":\"complete\"," +
                "\"symbol\":{\"selector\":\"exact:A\",\"kind\":\"classlike\",\"name\":\"A\"," +
                "\"qualifiedIdentity\":\"A\",\"file\":\"src/A.kt\"," +
                "\"range\":{\"startInclusive\":0,\"endExclusive\":7}," +
                "\"compilerEvidence\":{\"identity\":\"${compilerEvidence.identity.value}\"," +
                "\"signature\":{\"type\":\"class-like\",\"qualifiedIdentity\":\"A\"}}}}",
            projected.document.value,
        )
    }

    @Test
    fun `generated symbol serializer retains extension property receiver proof`() {
        val signature = CompilerSignatureDocument.Property(
            qualifiedIdentity = text("sample.tag"),
            receiver = CompilerReceiverDocument.Present(text("kotlin.String")),
            contextReceivers = BoundedProtocolList.create(listOf(text("sample.Context"))).refined(),
            returnType = text("kotlin.Int"),
        )
        val compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
        val result = SymbolDescribeResult(
            SymbolDocument.create(
                selector = text("exact:tag"),
                kind = SymbolKindDocument.PROPERTY,
                name = text("tag"),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(text("sample.tag")),
                file = text("src/Extensions.kt"),
                range = range(0, 12),
                compilerEvidence = compilerEvidence,
            ).refined(),
        )

        val projected = symbolDescribeCliProjector.project(
            OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_DESCRIBE, result)),
        ) as ProjectedCliOutcome.Complete

        assertTrue(projected.document.value.contains(
            "\"receiver\":{\"type\":\"present\",\"compilerType\":\"kotlin.String\"}",
        ))
        assertTrue(projected.document.value.contains(
            "\"contextReceivers\":[\"sample.Context\"]",
        ))
    }

    @Test
    fun `generated qualified documents append qualification after payload`() {
        val workspace = WorkspaceInspectResult(text("/repo"), WorkspaceStateDocument.RECONCILING)
        val workspaceProjected = workspaceInspectCliProjector.project(
            OperationOutcome.Qualified(
                evidence(CanonicalOperation.WORKSPACE_INSPECT, workspace),
                WorkspaceInspectQualification.RECONCILING,
            ),
        ) as ProjectedCliOutcome.Qualified
        val diagnosticsProjected = diagnosticCheckCliProjector.project(
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
                                    location = DiagnosticLocationDocument(
                                        candidateSelector = text("candidate:diagnostic"),
                                        file = text("src/A.kt"),
                                        range = DiagnosticRangeDocument.create(
                                            offset(4),
                                            offset(4),
                                        ).refined(),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                diagnosticCoverageQualification(),
            ),
        ) as ProjectedCliOutcome.Qualified

        assertEquals(
            "{\"operation\":\"workspace.inspect\",\"status\":\"qualified\"," +
                "\"canonicalRoot\":\"/repo\",\"state\":\"reconciling\"," +
                "\"qualification\":\"reconciling\"}",
            workspaceProjected.document.value,
        )
        assertEquals(
            "{\"operation\":\"diagnostic.check\",\"status\":\"qualified\"," +
                "\"diagnostics\":[{\"severity\":\"warning\",\"code\":\"UNUSED_SYMBOL\"," +
                "\"message\":\"warning\",\"location\":{" +
                "\"candidateSelector\":\"candidate:diagnostic\"," +
                "\"file\":\"src/A.kt\"," +
                "\"range\":{\"startInclusive\":4,\"endExclusive\":4}}}]," +
                "\"qualification\":{\"knownDiagnosticCount\":1," +
                "\"resultLimitReached\":false,\"analyzedFiles\":[]," +
                "\"limitations\":[{\"file\":\"src/A.kt\",\"reason\":\"indexing\"}]}}",
            diagnosticsProjected.document.value,
        )
    }

    @Test
    fun `qualified read outputs retain structured proof instead of one reason label`() {
        val relation = relationReadCliProjector.project(
            OperationOutcome.Qualified(
                evidence(CanonicalOperation.RELATION_READ, RelationReadResult(bounded(emptyList()))),
                relationQualification(),
            ),
        ) as ProjectedCliOutcome.Qualified
        val traversal = traversalRunCliProjector.project(
            OperationOutcome.Qualified(
                evidence(
                    CanonicalOperation.TRAVERSAL_RUN,
                    TraversalRunResult(text("/workspace"), bounded(emptyList())),
                ),
                traversalQualification(),
            ),
        ) as ProjectedCliOutcome.Qualified
        val diagnostics = diagnosticCheckCliProjector.project(
            OperationOutcome.Qualified(
                evidence(
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                    DiagnosticCheckResult(bounded(emptyList())),
                ),
                diagnosticResultLimitQualification(),
            ),
        ) as ProjectedCliOutcome.Qualified

        assertAll(
            {
                assertEquals(
                    "{\"knownMinimum\":0,\"limitations\":[\"result-limit-reached\"," +
                        "\"provider-incomplete\"],\"continuation\":\"${"a".repeat(64)}\"}",
                    relation.qualification().toString(),
                )
            },
            {
                assertEquals(
                    "{\"limitations\":[\"record-limit-reached\",\"one-hop-incomplete\"]," +
                        "\"relationLimitations\":[\"provider-incomplete\"]," +
                        "\"continuation\":\"${"b".repeat(64)}\"}",
                    traversal.qualification().toString(),
                )
            },
            {
                assertEquals(
                    "{\"knownDiagnosticCount\":0,\"resultLimitReached\":true," +
                        "\"analyzedFiles\":[\"src/A.kt\"],\"limitations\":[]}",
                    diagnostics.qualification().toString(),
                )
            },
        )
    }

    @Test
    fun `generated rejection serializer retains operation-specific reason`() {
        val projected = changeRecoverCliProjector.project(
            OperationOutcome.Rejected(ChangeRecoverRejection.JOURNAL_UNAVAILABLE),
        ) as ProjectedCliOutcome.Rejected

        assertEquals(
            "{\"operation\":\"change.recover\",\"status\":\"rejected\"," +
                "\"reason\":\"journal-unavailable\"}",
            projected.document.value,
        )
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int): ProtocolOffset = ProtocolOffset.parse(raw).refined()
    private fun depth(raw: Int): TraversalDepthDocument =
        TraversalDepthDocument.parse(raw).refined()

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

    private fun symbol(selector: String, name: String, file: String): SymbolDocument {
        val signature = CompilerSignatureDocument.ClassLike(text("sample.$name"))
        return SymbolDocument.create(
            selector = text(selector),
            kind = SymbolKindDocument.CLASSLIKE,
            name = text(name),
            qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(text("sample.$name")),
            file = text(file),
            range = range(0, name.length),
            compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined(),
        ).refined()
    }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value> evidence(
        operation: CanonicalOperation,
        value: Value,
    ): EvidenceEnvelope<Value> = EvidenceEnvelope(
        operation.id,
        EvidenceGeneration.parse(1).refined(),
        value,
    )

    private fun relationQualification(): RelationReadQualification =
        RelationReadQualification.create(
            RelationKnownMinimumDocument.parse(0).refined(),
            listOf(
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            RelationContinuationDocument.parse("a".repeat(64)).refined(),
        ).refined()

    private fun traversalQualification(): TraversalRunQualification =
        TraversalRunQualification.create(
            listOf(
                TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
            ),
            listOf(RelationLimitationDocument.PROVIDER_INCOMPLETE),
            TraversalContinuationDocument.parse("b".repeat(64)).refined(),
        ).refined()

    private fun diagnosticCoverageQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
            DiagnosticKnownCountDocument.parse(1).refined(),
            resultLimitReached = false,
            analyzedFiles = emptyList(),
            limitations = listOf(
                DiagnosticLimitationDocument(
                    text("src/A.kt"),
                    DiagnosticLimitationReasonDocument.INDEXING,
                ),
            ),
        ).refined()

    private fun diagnosticResultLimitQualification(): DiagnosticCheckQualification =
        DiagnosticCheckQualification.create(
            DiagnosticKnownCountDocument.parse(0).refined(),
            resultLimitReached = true,
            analyzedFiles = listOf(text("src/A.kt")),
            limitations = emptyList(),
        ).refined()

    private fun ProjectedCliOutcome.Qualified.qualification() =
        Json.parseToJsonElement(document.value).jsonObject.getValue("qualification")

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
