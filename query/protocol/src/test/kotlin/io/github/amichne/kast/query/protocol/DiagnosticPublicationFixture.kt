package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import java.nio.file.Path

internal class DiagnosticPublicationFixture {
    val owner = RelationPagingFixture.live()
    val query = DiagnosticScopeQuery.parse(owner.authority, "src").refined()
    val request = DiagnosticCheckRequest(ProtocolText.parse("src").refined(), ProtocolCount.parse(4).refined())
    val budget =
        ResourceBudget(
            ResultLimit.parse(4).refined(),
            WorkUnitLimit.parse(100).refined(),
            ElapsedTimeLimitMillis.parse(1000).refined(),
        )
    val scope = DiagnosticScope.fromCanonicalPaths(owner.authority, listOf(Path.of("/workspace/src/A.kt"))).refined()
    val file = scope.files.single()
    val facts =
        (0..3).map {
            DiagnosticFact.fromBoundary(
                    scope,
                    file,
                    it,
                    it + 1,
                    DiagnosticSeverity.WARNING,
                    "CASE_$it",
                    "diagnostic $it",
                )
                .refined()
        }

    fun completed() =
        DiagnosticScanResult.Complete(
            DiagnosticScanPage(facts, listOf(file), emptySet(), DiagnosticScanInventory.Exhausted(listOf(file)))
        )

    fun protocol(
        store: DiagnosticCheckpointStore,
        publication: Capture,
        scan: suspend () -> DiagnosticScanResult,
    ) =
        CanonicalDiagnosticCheckProtocol(
            DiagnosticScanOperations { _, _ -> scan() },
            owner.references,
            store,
            publication,
        )

    fun measure(page: DiagnosticPublishedPage): QueryRetentionByteCount {
        val encoded = CanonicalOperationWireBindings.diagnosticCheck.encodeOutcome(page) as WireEncoding.Encoded
        return QueryRetentionByteCount.parse(encoded.document.toByteArray(Charsets.UTF_8).size.toLong()).refined()
    }

    fun prefix(
        page: OperationOutcome.Complete<io.github.amichne.kast.protocol.contract.DiagnosticCheckResult>,
        count: Int,
        token: ProtocolText,
    ) =
        OperationOutcome.Qualified(
            page.evidence.copy(
                payload =
                    page.evidence.payload.copy(
                        diagnostics = bounded(page.evidence.payload.diagnostics.values.take(count)),
                        progress =
                            page.evidence.payload.progress?.copy(
                                stage = DiagnosticProgressStage.OUTPUT,
                                stop = DiagnosticProgressStop.OUTPUT_PENDING,
                            ),
                    )
            ),
            DiagnosticCheckQualification.create(
                    page.evidence.payload.progress!!.knownDiagnosticCount,
                    true,
                    page.evidence.payload.progress!!.analyzedFiles,
                    emptyList(),
                    token,
                )
                .refined(),
        )

    class Capture : DiagnosticExecutionPublication {
        lateinit var claim: DiagnosticExecutionClaim

        override fun prepare(
            store: DiagnosticCheckpointStore,
            claim: DiagnosticExecutionClaim,
            page: DiagnosticPublishedPage,
        ): DiagnosticExecutionPublicationResult {
            this.claim = claim
            return DiagnosticExecutionPublicationResult.PREPARED
        }
    }

    fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Case fixture rejected: $failure")
        }

    fun OperationOutcome.Complete<io.github.amichne.kast.protocol.contract.DiagnosticCheckResult>.dropFacts(
        count: Int
    ) =
        OperationOutcome.Complete(
            evidence.copy(
                payload = evidence.payload.copy(diagnostics = bounded(evidence.payload.diagnostics.values.drop(count)))
            )
        )
}
