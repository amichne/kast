package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.relation.contract.*
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path

/** Four deterministic ordered facts through the real admission, continuation and projection owners. */
class RelationPagingFixture(val authority: SemanticReadAuthority, subjectName: String = "subject") {
    val references = CanonicalQueryReferences()
    val selector =
        SymbolSelector.issue(
            authority,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.Workspace(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                authority.workspaceRoot,
                                Path.of(authority.workspaceRoot.value).resolve("Subject.kt"),
                            )
                            .refined()
                    ),
                    0,
                    6,
                    subjectName,
                    "sample.$subjectName",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("sample.$subjectName", null, emptyList(), emptyList(), 0)
                        .refined(),
                )
                .refined(),
        )
    val exact = (references.issueExact(selector) as ExactSelectorIssuance.Issued).selector
    val budget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(3).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1_000).refined(),
            ),
            RelationByteLimit.parse(100_000).refined(),
        )
    val consumed = mutableListOf<Long>()
    val protocol = CanonicalRelationReadProtocol(RelationOperations(::read), references)
    private val startRequest = RelationRequest.start(selector, RelationMeaning.References, budget)
    private val cursors =
        (0L..3L).runningFold(startRequest.providerCursor) { cursor, index ->
            cursor.advance(
                RelationProviderItemDescriptor.parse(fact(startRequest, index).canonicalProjection()).refined()
            )
        }

    suspend fun page(position: RelationReadPositionDocument = RelationReadPositionDocument.Start) =
        protocol.execute(request(position), authority, budget)

    fun request(position: RelationReadPositionDocument) =
        RelationReadRequest(
            exact,
            RelationKindDocument.REFERENCES,
            ProtocolCount.parse(3).refined(),
            position,
        )

    private suspend fun read(request: RelationRequest): RelationReadResult {
        val start = request.providerCursor.nextPosition.value
        check(request.providerCursor == cursors[start.toInt()]) { "Provider prefix changed" }
        val end = minOf(4, start + request.budget.resources.resultLimit.value)
        var cursor = request.providerCursor
        val facts =
            (start until end).map { index ->
                consumed += index
                val fact = fact(request, index)
                cursor = cursor.advance(RelationProviderItemDescriptor.parse(fact.canonicalProjection()).refined())
                fact
            }
        val batch =
            RelationBatch.create(
                    request,
                    facts,
                    RelationByteCount.parse(facts.sumOf { it.canonicalProjection().toByteArray().size.toLong() })
                        .refined(),
                    RelationWorkCount.parse(end - start).refined(),
                    RelationResultCount.parse(facts.size).refined(),
                )
                .refined()
        return if (end == 4L) RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        else {
            val compilation =
                RelationCompilation.qualifiedResumable(
                        batch,
                        setOf(RelationLimitation.RESULT_LIMIT_REACHED),
                        cursor,
                    )
                    .refined()
            RelationReadResult.Qualified(batch, compilation.coverage)
        }
    }

    private fun fact(request: RelationRequest, index: Long): RelationFact =
        RelationFact.create(
                request,
                request.subject,
                request.subject,
                RelationOccurrence.fromBoundary(request.subject.file, 10 + index.toInt() * 2, 11 + index.toInt() * 2)
                    .refined(),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()

    companion object {
        fun published(): RelationPagingFixture =
            RelationPagingFixture(SemanticReadLease(root(), EvidenceGeneration.parse(7).refined()))

        fun live(): RelationPagingFixture = RelationPagingFixture(LiveReadAuthorityFixture.create(root()))

        private fun root() = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

        private fun <T, F> Refinement<T, F>.refined(): T =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Fixture rejection: $failure")
            }
    }
}
