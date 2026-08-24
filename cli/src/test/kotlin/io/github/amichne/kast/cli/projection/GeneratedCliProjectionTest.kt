package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.ProjectedCliOutcome
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeneratedCliProjectionTest {
    @Test
    fun `generated discovery serializer preserves every closed item variant`() {
        val result = SymbolDiscoverResult(
            bounded(
                listOf(
                    SymbolDiscoveryDocument.File(text("A.kt"), text("src/A.kt")),
                    SymbolDiscoveryDocument.Declaration(
                        candidateSelector = text("candidate:A"),
                        kind = SymbolDiscoveryKindDocument.CLASS,
                        name = text("A"),
                        file = text("src/A.kt"),
                        offset = offset(3),
                    ),
                    SymbolDiscoveryDocument.TextMatch(
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
                "{\"type\":\"file\",\"name\":\"A.kt\",\"file\":\"src/A.kt\"}," +
                "{\"type\":\"declaration\",\"candidateSelector\":\"candidate:A\"," +
                "\"kind\":\"class\",\"name\":\"A\",\"file\":\"src/A.kt\",\"offset\":3}," +
                "{\"type\":\"text-match\",\"query\":\"TODO\",\"file\":\"src/A.kt\"," +
                "\"range\":{\"startInclusive\":4,\"endExclusive\":8}}]}",
            projected.document.value,
        )
    }

    @Test
    fun `generated symbol serializer preserves explicit unavailable identity`() {
        val result = SymbolDescribeResult(
            SymbolDocument(
                selector = text("exact:A"),
                kind = SymbolKindDocument.CLASSLIKE,
                name = text("A"),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Unavailable,
                file = text("src/A.kt"),
                range = range(0, 7),
            ),
        )

        val projected = symbolDescribeCliProjector.project(
            OperationOutcome.Complete(evidence(CanonicalOperation.SYMBOL_DESCRIBE, result)),
        ) as ProjectedCliOutcome.Complete

        assertEquals(
            "{\"operation\":\"symbol.describe\",\"status\":\"complete\"," +
                "\"symbol\":{\"selector\":\"exact:A\",\"kind\":\"classlike\",\"name\":\"A\"," +
                "\"qualifiedIdentity\":null,\"file\":\"src/A.kt\"," +
                "\"range\":{\"startInclusive\":0,\"endExclusive\":7}}}",
            projected.document.value,
        )
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
                    DiagnosticCheckResult(bounded(listOf(text("warning")))),
                ),
                DiagnosticCheckQualification.COVERAGE_INCOMPLETE,
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
                "\"diagnostics\":[\"warning\"],\"qualification\":\"coverage-incomplete\"}",
            diagnosticsProjected.document.value,
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

    private fun range(start: Int, end: Int): SourceRangeDocument =
        SourceRangeDocument.create(offset(start), offset(end)).refined()

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

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
