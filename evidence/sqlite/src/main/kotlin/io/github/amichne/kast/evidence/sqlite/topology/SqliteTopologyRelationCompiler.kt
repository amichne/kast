package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointRevalidationFailure
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderCursor
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologySnapshotContent
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import java.nio.charset.StandardCharsets

/**
 * SQLite implementation of the one-hop relation compiler used by public repository traversal.
 *
 * Proof transition: `(PublishedTopologySnapshot, TopologySnapshotContent) ->
 * SqliteTopologyRelationCompiler`.
 *
 * The returned capability retains one already re-admitted exact snapshot for all one-hop reads in
 * one traversal request. Each read preserves the request lease, selector, scope, edge meaning,
 * pagination, and budgets. It has no K2, IntelliJ, Gradle, module-model, or filesystem capability.
 */
class SqliteTopologyRelationCompiler private constructor(
    private val content: TopologySnapshotContent,
) : RelationCompilerPort {
    private val snapshot: PublishedTopologySnapshot = content.snapshot

    companion object {
        /**
         * Proof transition: `(PublishedTopologySnapshot, TopologySnapshotContentReader) ->
         * SqliteTopologyRelationCompilerOpening`.
         *
         * Establishes either one request-local compiler retaining re-admitted content for the
         * exact published snapshot or the reader's closed [TopologySnapshotReadFailure]. Physical
         * snapshot reads are permitted only in this opening transition; one-hop compilation may
         * extract only from the retained [TopologySnapshotContent].
         */
        fun open(
            snapshot: PublishedTopologySnapshot,
            reader: TopologySnapshotContentReader,
        ): SqliteTopologyRelationCompilerOpening = when (val loaded = reader.read(snapshot)) {
            is TopologySnapshotContentRead.Loaded -> if (
                loaded.content.snapshot.identity == snapshot.identity &&
                loaded.content.snapshot.manifest == snapshot.manifest
            ) {
                SqliteTopologyRelationCompilerOpening.Opened(
                    SqliteTopologyRelationCompiler(loaded.content),
                )
            } else {
                SqliteTopologyRelationCompilerOpening.Rejected(
                    TopologySnapshotReadFailure.CORRUPT_SNAPSHOT,
                )
            }
            is TopologySnapshotContentRead.Rejected ->
                SqliteTopologyRelationCompilerOpening.Rejected(loaded.failure)
        }
    }

    override suspend fun read(request: RelationRequest): RelationCompilation {
        if (request.subject.lease.workspaceRoot != snapshot.identity.lease.workspaceRoot) {
            return RelationCompilation.Rejected(RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH)
        }
        if (request.subject.lease.generation != snapshot.identity.lease.generation) {
            return RelationCompilation.Rejected(RelationCompilerRejection.GENERATION_MOVED)
        }
        val subjects = content.symbols.asSequence()
            .filter { it.evidence.compilerIdentity == request.subject.compilerIdentity }
            .mapNotNull { candidate ->
                when (val validation = RevalidatedTopologySubject.validate(
                    request.subject,
                    candidate,
                )) {
                    is Refinement.Refined -> validation.value
                    is Refinement.Rejected -> null
                }
            }
            .toList()
        val subject = if (subjects.size == 1) subjects.single()
        else return RelationCompilation.Rejected(RelationCompilerRejection.STALE_SELECTOR)
        if (!subject.inside(request.subject.scope)) {
            return RelationCompilation.Rejected(RelationCompilerRejection.OUTSIDE_SCOPE)
        }
        val facts = content.edges.asSequence()
            .filter { subject.matches(request.meaning, it) }
            .filter { it.source.inside(request.subject.scope) && it.target.inside(request.subject.scope) }
            .map { edge -> edge.toRelationFact(request) }
            .toList()
        if (facts.any { it is RelationFactProjection.Rejected }) {
            return RelationCompilation.Rejected(
                RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
            )
        }
        return page(
            request,
            facts.map { (it as RelationFactProjection.Projected).fact }.sorted(),
        )
    }

    private fun page(request: RelationRequest, facts: List<RelationFact>): RelationCompilation {
        val requestedCursor = request.providerCursor
        val offset = requestedCursor.nextPosition.value
        if (offset > facts.size.toLong()) {
            return RelationCompilation.Rejected(
                RelationCompilerRejection.CONTINUATION_CURSOR_MOVED,
            )
        }
        var observedPrefix = RelationProviderCursor.start(requestedCursor.provider)
        facts.take(offset.toInt()).forEach { fact ->
            observedPrefix = observedPrefix.advance(fact.providerDescriptor())
        }
        if (observedPrefix != requestedCursor) {
            return RelationCompilation.Rejected(
                RelationCompilerRejection.CONTINUATION_CURSOR_MOVED,
            )
        }
        val resultLimit = request.budget.resources.resultLimit.value
        val workLimit = request.budget.resources.workUnitLimit.value
        val byteLimit = request.budget.returnedBytes.value
        val page = mutableListOf<RelationFact>()
        var bytes = 0L
        var boundary: RelationPageBoundary = RelationPageBoundary.NotReached
        for (fact in facts.drop(offset.toInt())) {
            val factBytes = fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size
            boundary = when {
                page.size >= resultLimit -> RelationPageBoundary.Reached.RESULT_LIMIT
                page.size.toLong() >= workLimit -> RelationPageBoundary.Reached.WORK_LIMIT
                bytes + factBytes > byteLimit -> RelationPageBoundary.Reached.BYTE_LIMIT
                else -> RelationPageBoundary.NotReached
            }
            if (boundary is RelationPageBoundary.Reached) {
                break
            }
            page += fact
            bytes += factBytes
        }
        val byteCount = when (val parsed = RelationByteCount.parse(bytes)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return contractRejected()
        }
        val workCount = when (val parsed = RelationWorkCount.parse(page.size.toLong())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return contractRejected()
        }
        val batch = when (val admitted = RelationBatch.create(
            request,
            page,
            byteCount,
            workCount,
            RelationResultCount.parse(page.size).refinedOrNull() ?: return contractRejected(),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return contractRejected()
        }
        val nextOffset = offset + page.size
        if (nextOffset == facts.size.toLong()) return RelationCompilation.complete(batch)
        val limitations = when (val reached = boundary) {
            RelationPageBoundary.NotReached -> return contractRejected()
            is RelationPageBoundary.Reached -> setOf(reached.limitation)
        }
        var next = requestedCursor
        page.forEach { fact ->
            next = next.advance(fact.providerDescriptor())
        }
        return when (
            val qualified = RelationCompilation.qualifiedResumable(batch, limitations, next)
        ) {
            is Refinement.Refined -> qualified.value
            is Refinement.Rejected -> contractRejected()
        }
    }

    private fun TopologyEdge.toRelationFact(request: RelationRequest): RelationFactProjection {
        val outgoing = request.meaning == RelationMeaning.Callees
        val related = if (outgoing) target else source
        val resolved = when (val endpoint = RelationEndpoint.resolve(
            request.subject.lease,
            request.subject.scope,
            related.evidence,
        )) {
            is Refinement.Refined -> endpoint.value
            is Refinement.Rejected -> return RelationFactProjection.Rejected
        }
        val occurrence = when (val admitted = RelationOccurrence.fromBoundary(
            source.evidence.file,
            this.occurrence.startInclusive,
            this.occurrence.endExclusive,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return RelationFactProjection.Rejected
        }
        val provenance = when (source.file.sourceRoot.provenance) {
            SourceRootProvenance.Authored -> RelationProvenance.K2_AUTHORED_SOURCE
            SourceRootProvenance.Generated -> RelationProvenance.K2_GENERATED_SOURCE
            is SourceRootProvenance.Unknown -> return RelationFactProjection.Rejected
        }
        val sourceEndpoint = if (outgoing) request.subject else resolved
        val targetEndpoint = if (outgoing) resolved else request.subject
        return when (val fact = RelationFact.create(
            request,
            sourceEndpoint,
            targetEndpoint,
            occurrence,
            provenance,
        )) {
            is Refinement.Refined -> RelationFactProjection.Projected(fact.value)
            is Refinement.Rejected -> RelationFactProjection.Rejected
        }
    }

    private fun contractRejected(): RelationCompilation = RelationCompilation.Rejected(
        RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
    )

    private fun RelationFact.providerDescriptor(): RelationProviderItemDescriptor =
        RelationProviderItemDescriptor.parse(canonicalProjection()).refinedOrNull()
            ?: error("A canonical relation fact is never blank")

    private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
}

sealed interface SqliteTopologyRelationCompilerOpening {
    data class Opened(
        val compiler: SqliteTopologyRelationCompiler,
    ) : SqliteTopologyRelationCompilerOpening

    data class Rejected(
        val failure: TopologySnapshotReadFailure,
    ) : SqliteTopologyRelationCompilerOpening
}

/** Exact topology symbol carrying its retained relation-endpoint revalidation proof. */
private class RevalidatedTopologySubject private constructor(
    @Suppress("unused")
    private val proof: RevalidatedRelationEndpoint,
    private val symbol: TopologySymbol,
) {
    fun inside(scope: SymbolSearchScope): Boolean = symbol.inside(scope)

    fun matches(meaning: RelationMeaning, edge: TopologyEdge): Boolean =
        edge.matches(meaning, symbol)

    companion object {
        /**
         * Proof transition: `(RelationEndpoint, TopologySymbol) -> Refinement<
         * RevalidatedTopologySubject, RelationEndpointRevalidationFailure>`.
         *
         * Establishes that the retained topology symbol is exactly the requested endpoint and
         * keeps that proof attached while relation edges are selected. The closed expected
         * failure is [RelationEndpointRevalidationFailure]. Raw topology-symbol extraction is
         * confined to this SQLite relation adapter.
         */
        fun validate(
            endpoint: RelationEndpoint,
            symbol: TopologySymbol,
        ): Refinement<RevalidatedTopologySubject, RelationEndpointRevalidationFailure> = when (
            val validation = RevalidatedRelationEndpoint.validate(endpoint, symbol.evidence)
        ) {
            is Refinement.Refined -> Refinement.Refined(
                RevalidatedTopologySubject(validation.value, symbol),
            )
            is Refinement.Rejected -> Refinement.Rejected(validation.failure)
        }
    }
}

private sealed interface RelationPageBoundary {
    data object NotReached : RelationPageBoundary

    enum class Reached(
        val limitation: io.github.amichne.kast.relation.contract.RelationLimitation,
    ) : RelationPageBoundary {
        RESULT_LIMIT(
            io.github.amichne.kast.relation.contract.RelationLimitation.RESULT_LIMIT_REACHED,
        ),
        WORK_LIMIT(io.github.amichne.kast.relation.contract.RelationLimitation.WORK_LIMIT_REACHED),
        BYTE_LIMIT(io.github.amichne.kast.relation.contract.RelationLimitation.BYTE_LIMIT_REACHED),
    }
}

private sealed interface RelationFactProjection {
    data class Projected(val fact: RelationFact) : RelationFactProjection
    data object Rejected : RelationFactProjection
}

private fun TopologyEdge.matches(meaning: RelationMeaning, subject: TopologySymbol): Boolean =
    when (meaning) {
        RelationMeaning.Callees -> kind == TopologyEdgeKind.CALL && source == subject
        RelationMeaning.Callers -> kind == TopologyEdgeKind.CALL && target == subject
        RelationMeaning.References -> kind == TopologyEdgeKind.REFERENCE && target == subject
        RelationMeaning.TypeUses -> kind == TopologyEdgeKind.TYPE_USE && target == subject
        RelationMeaning.Implementations,
        RelationMeaning.Inheritors,
            -> kind == TopologyEdgeKind.INHERITANCE && target == subject
        RelationMeaning.Overrides -> kind == TopologyEdgeKind.OVERRIDE && target == subject
    }

private fun TopologySymbol.inside(scope: SymbolSearchScope): Boolean {
    val root = file.sourceRoot
    val targetMatches = when (scope) {
        is SymbolSearchScope.ExactFile -> evidence.file.stableValue == scope.file.value
        is SymbolSearchScope.Module -> root.owner.module == scope.module
        is SymbolSearchScope.SourceSet ->
            root.owner.project == scope.project && root.owner.sourceSet == scope.sourceSet
        is SymbolSearchScope.GradleProject -> root.owner.project == scope.project
        is SymbolSearchScope.Workspace -> true
    }
    val testSource = root.owner.sourceSet.value.lowercase().contains("test")
    val kindMatches = when (scope.sourceKinds) {
        SymbolSourceKindPolicy.PRODUCTION_ONLY -> !testSource
        SymbolSourceKindPolicy.TEST_ONLY -> testSource
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST -> true
    }
    val provenanceMatches = when (scope.generatedSources) {
        SymbolGeneratedSourcePolicy.EXCLUDE -> root.provenance == SourceRootProvenance.Authored
        SymbolGeneratedSourcePolicy.INCLUDE -> root.provenance !is SourceRootProvenance.Unknown
    }
    return targetMatches && kindMatches && provenanceMatches
}
