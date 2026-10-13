package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.RelationOwnershipUnavailableCauseDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryRelationGraphDirectionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryRelationGraphIneligibility
import io.github.amichne.kast.protocol.wire.presentation.QueryRelationGraphOwnershipCliDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryRelationGraphProjection
import io.github.amichne.kast.protocol.wire.presentation.QueryResultItemCliDocument
import io.github.amichne.kast.protocol.wire.presentation.projectRetainedRelationGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RetainedRelationGraphProjectionTest {
    private val basis =
        (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value

    @Test
    fun `mixed row kinds keep the existing full fallback`() {
        val fact = occurrence(100, "owner0")
        val mixed =
            listOf(
                row(fact, 1),
                QueryResultItemCliDocument.ReferenceEdge(
                    "result-row:v1:00000000-0000-0000-0000-000000000001",
                    "witness:v1:" + "0".repeat(64),
                ),
            )
        assertEquals(
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_KIND),
            projectRetainedRelationGraph(basis, mixed, emptyList()),
        )
    }

    @Test
    fun `file context and unavailable ownership preserve finite qualifications`() {
        val base = occurrence(100, "owner0")
        val imported =
            base.copy(
                context = RelationReferenceContextDocument.IMPORT,
                ownership = RelationReferenceOwnershipWireDocument.FileScoped(RelationReferenceContextDocument.IMPORT),
            )
        val unavailable =
            base.copy(
                ownership =
                    RelationReferenceOwnershipWireDocument.Unavailable(
                        RelationOwnershipUnavailableCauseDocument.UNRESOLVED_DECLARATION
                    )
            )
        val graph = project(listOf(imported, unavailable)).graph
        assertEquals(1, graph.nodes.size)
        assertEquals(QueryRelationGraphDirectionCliDocument.FILE_CONTEXT_TO_TARGET, graph.witnesses.first().direction)
        assertEquals(QueryRelationGraphDirectionCliDocument.OWNER_UNPROVEN, graph.witnesses.last().direction)
        assertEquals(
            QueryRelationGraphOwnershipCliDocument.Unavailable(
                RelationOwnershipUnavailableCauseDocument.UNRESOLVED_DECLARATION
            ),
            graph.witnesses.last().ownership,
        )
        assertEquals(RelationReferenceContextDocument.IMPORT, graph.witnesses.first().context)
    }

    @Test
    fun `distinct same-name identities and call sites preserve stable IDs across page partitions`() {
        val first = occurrence(100, "owner0")
        val second = occurrence(120, "owner1")
        val whole = project(listOf(first, second))
        val prefix = project(listOf(first))
        val suffix = project(listOf(second))
        assertEquals(3, whole.graph.nodes.size)
        assertEquals(2, whole.graph.witnesses.size)
        assertEquals(prefix.graph.witnesses.single(), whole.graph.witnesses.first())
        assertEquals(suffix.graph.witnesses.single(), whole.graph.witnesses.last())
        assertEquals(prefix.graph.nodes.first(), suffix.graph.nodes.first())
        assertNotEquals(prefix.graph.nodes.last().id, suffix.graph.nodes.last().id)
        assertEquals(whole.graph.witnesses.map { it.id }, whole.graph.referenceObservations)
    }

    @Test
    fun `conflicting descriptions for one exact selector cannot enter a shared node table`() {
        val first = occurrence(100, "owner0")
        val conflicting = first.copy(target = first.target.copy(name = "different"))
        val projection = projectRetainedRelationGraph(basis, listOf(row(first, 1), row(conflicting, 2)), emptyList())
        assertEquals(
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.IDENTITY_CONFLICT),
            projection,
        )
    }

    @Test
    fun `unequal full compiler signatures reject before compact node projection`() {
        val first = occurrence(100, "owner0")
        val compiler = first.target.compilerEvidence
        val signature = compiler.signature as CompilerSignatureWireDocument.Function
        val conflicting =
            first.copy(
                target =
                    first.target.copy(
                        compilerEvidence = compiler.copy(signature = signature.copy(valueParameters = listOf("Other")))
                    )
            )
        assertEquals(
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.IDENTITY_CONFLICT),
            projectRetainedRelationGraph(basis, listOf(row(first, 1), row(conflicting, 2)), emptyList()),
        )
    }

    @Test
    fun `absent retained row identity and mismatched target cannot gain compact identities`() {
        val fact = occurrence(100, "owner0")
        assertEquals(
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.ROW_ID_ABSENT),
            projectRetainedRelationGraph(basis, listOf(row(fact, 1).copy(rowId = null)), emptyList()),
        )
        assertEquals(
            QueryRelationGraphProjection.Ineligible(QueryRelationGraphIneligibility.TARGET_REFERENCE_MISMATCH),
            projectRetainedRelationGraph(basis, listOf(row(fact, 1).copy(ref = "exact:v4:different")), emptyList()),
        )
    }

    private fun project(facts: List<RelationReferenceOccurrenceWireDocument>): QueryRelationGraphProjection.Projected =
        projectRetainedRelationGraph(basis, facts.mapIndexed { index, fact -> row(fact, index + 1) }, facts)
            as QueryRelationGraphProjection.Projected

    private fun row(fact: RelationReferenceOccurrenceWireDocument, index: Int) =
        QueryResultItemCliDocument.ReferenceOccurrence(
            fact.target.selector,
            fact,
            "result-row:v1:00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        )

    private fun occurrence(start: Int, owner: String) =
        RelationReferenceOccurrenceWireDocument(
            target = symbol("target"),
            meaning = RelationKindWireDocument.REFERENCES,
            occurrence =
                RelationOccurrenceWireDocument(
                    "candidate:v1:$start",
                    "/workspace/Source.kt",
                    SourceRangeWireDocument(start, start + 8),
                ),
            context = RelationReferenceContextDocument.CODE,
            ownership = RelationReferenceOwnershipWireDocument.DeclarationOwned(symbol(owner)),
            provenance = RelationProvenanceWireDocument.K2_AUTHORED_SOURCE,
            coverage = RelationFactCoverageWireDocument.EXACT_COMPILER_CONFIRMED,
        )

    private fun symbol(identity: String) =
        SymbolWireDocument(
            selector = "exact:v4:$identity",
            kind = SymbolKindWireDocument.FUNCTION,
            name = "sameName",
            qualifiedIdentity = "fixture.$identity",
            file = "/workspace/Source.kt",
            range = SourceRangeWireDocument(0, 1_000),
            compilerEvidence =
                CompilerSymbolEvidenceWireDocument(
                    "compiler:$identity",
                    CompilerSignatureWireDocument.Function(
                        qualifiedIdentity = "fixture.$identity",
                        receiver = CompilerReceiverWireDocument.Absent,
                        contextReceivers = emptyList(),
                        valueParameters = emptyList(),
                        typeParameterCount = 0,
                    ),
                ),
        )
}
