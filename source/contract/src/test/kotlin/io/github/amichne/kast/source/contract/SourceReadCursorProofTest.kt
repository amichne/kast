package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SourceReadCursorProofTest {
    @Test
    fun `successor cannot rebind already admitted semantic selection`() {
        val fixture = fixture()
        val first =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    1,
                    traversal(fixture.region, 1),
                )
                .refined()
        val previous = first.admit(first.snapshot.context, fixture.request).refined()
        for (changed in
            listOf(
                fixture.request.copy(region = RegionSelection.Anchor),
                fixture.request.copy(text = TextProjection.Complete),
                fixture.request.copy(outputIdentity = SourceReadOutputIdentity.COMPACT),
                fixture.request.copy(
                    entities = EntitySelection.matching(Containment.DIRECT, listOf(EntityFilter.References)).refined()
                ),
            )) {
            assertEquals(
                Refinement.Rejected(SourceReadCursorFailure.REQUEST_MISMATCH),
                SourceReadCursorProof.create(changed, fixture.region, previous, 2, traversal(fixture.region, 2)),
            )
        }
        assertIs<Refinement.Refined<SourceReadCursorProof>>(
            SourceReadCursorProof.create(
                fixture.request.copy(entityLimit = SourceEntityLimit.parse(20).refined()),
                fixture.region,
                previous,
                2,
                traversal(fixture.region, 2),
            )
        )
    }

    @Test
    fun `filtered input can advance structural work without advancing output ordinal`() {
        val fixture = fixture()
        val first =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    0,
                    traversal(fixture.region, 1),
                )
                .refined()
        val previous = first.admit(first.snapshot.context, fixture.request).refined()
        assertEquals(
            0,
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    previous,
                    0,
                    traversal(fixture.region, 2),
                )
                .refined()
                .nextOrdinal
                .value,
        )
        assertEquals(
            Refinement.Rejected(SourceReadCursorFailure.NON_ADVANCING),
            SourceReadCursorProof.create(fixture.request, fixture.region, previous, 0, traversal(fixture.region, 1)),
        )
    }

    @Test
    fun `resumed cursor carries exact selected source and permits changed grants`() {
        val fixture = fixture()
        val proof =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    3,
                    traversal(fixture.region, 3),
                )
                .refined()
        assertSame(fixture.region, proof.region)
        assertSame(fixture.region.snapshot, proof.snapshot)
        val changedGrant =
            fixture.request.copy(
                entityLimit = SourceEntityLimit.parse(20).refined(),
                textByteLimit = SourceTextByteLimit.parse(5).refined(),
                page = SourceReadPage.Continue(token()),
            )
        val continued = proof.admit(proof.snapshot.context, changedGrant).refined()
        assertEquals(3, continued.startOrdinal)
        assertSame(proof, assertIs<SourceReadEntityCursor.Continued>(continued).proof)
    }

    @Test
    fun `authority and semantic request mismatches retain distinct finite causes`() {
        val fixture = fixture()
        val proof =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    1,
                    traversal(fixture.region, 1),
                )
                .refined()
        val context = proof.snapshot.context as SourceReadContext.Published
        assertEquals(
            Refinement.Rejected(SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH),
            proof.admit(
                context.copy(sourceState = WorkspaceStateIdentity.parse("workspace-state-v1|changed").refined()),
                fixture.request,
            ),
        )
        for (changed in
            listOf(
                fixture.request.copy(outputIdentity = SourceReadOutputIdentity.COMPACT),
                fixture.request.copy(entities = EntitySelection.None),
                fixture.request.copy(region = RegionSelection.Anchor),
                fixture.request.copy(text = TextProjection.Complete),
            )) {
            assertEquals(
                Refinement.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH),
                proof.admit(context, changed),
            )
        }
    }

    @Test
    fun `successor creation requires progress under the exact captured source`() {
        val fixture = fixture()
        val first =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    4,
                    traversal(fixture.region, 4),
                )
                .refined()
        val previous = first.admit(first.snapshot.context, fixture.request).refined()
        for (position in listOf(-1, 0, 3, 4)) {
            assertEquals(
                Refinement.Rejected(SourceReadCursorFailure.NON_ADVANCING),
                SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    previous,
                    position,
                    traversal(fixture.region, maxOf(1, position).toLong()),
                ),
            )
        }
        assertEquals(
            5,
            SourceReadCursorProof.create(fixture.request, fixture.region, previous, 5, traversal(fixture.region, 5))
                .refined()
                .nextOrdinal
                .value,
        )
        val changed = fixture("class Changed")
        assertEquals(
            Refinement.Rejected(SourceReadCursorFailure.AUTHORITY_MISMATCH),
            SourceReadCursorProof.create(fixture.request, changed.region, previous, 5, traversal(changed.region, 5)),
        )
        assertEquals(
            Refinement.Rejected(SourceReadCursorFailure.ENTITY_STREAM_ABSENT),
            SourceReadCursorProof.create(
                fixture.request.copy(entities = EntitySelection.None),
                fixture.region,
                SourceReadEntityCursor.First,
                1,
                traversal(fixture.region, 1),
            ),
        )
    }

    @Test
    fun `entity selection cannot mutate after retained binding`() {
        val fixture = fixture()
        val selection = fixture.request.entities as EntitySelection.Matching
        val proof =
            SourceReadCursorProof.create(
                    fixture.request,
                    fixture.region,
                    SourceReadEntityCursor.First,
                    1,
                    traversal(fixture.region, 1),
                )
                .refined()
        assertFailsWith<UnsupportedOperationException> { (selection.filters as MutableList<EntityFilter>).clear() }
        val equivalent =
            fixture.request.copy(
                entities = EntitySelection.matching(Containment.DESCENDANTS, listOf(EntityFilter.References)).refined()
            )
        assertIs<Refinement.Refined<SourceReadEntityCursor>>(proof.admit(proof.snapshot.context, equivalent))
    }

    private fun traversal(region: SourceSelector, revision: Long): SourceEntityTraversalState =
        SourceEntityTraversalState.create(
                region,
                revision,
                listOf(
                    SourceEntityTraversalTask.Visit(
                        SourceEntityElementLocator(
                            region.range,
                            SourceEntityElementDescriptor.parse("fixture.node").refined(),
                        ),
                        SourceEntityStructuralParent(region, SourceNestingDepth.parse(0).refined()),
                        null,
                        SourceEntitySiblingPolicy.SINGLE,
                    )
                ),
            )
            .refined()

    private data class Fixture(val request: SourceReadRequest, val region: SourceSelector)

    private fun fixture(text: String = "class Subject"): Fixture {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val snapshot =
            SourceSnapshot.create(
                SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
                WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
                SymbolDiscoveryFileIdentity.Workspace(
                    CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Subject.kt")).refined()
                ),
                SourceTextIdentity.fromNormalizedCommittedText(text),
                Utf16CodeUnitCount.parse(text.length).refined(),
            )
        val selector =
            SourceSelector.issueRoot(
                SourceRange.create(
                        snapshot,
                        Utf16CodeUnitOffset.parse(0).refined(),
                        Utf16CodeUnitOffset.parse(text.length).refined(),
                    )
                    .refined(),
                SourceRegionKind.FILE,
            )
        return Fixture(
            SourceReadRequest(
                SourceReadAnchor.Source(selector),
                RegionSelection.File,
                EntitySelection.matching(Containment.DESCENDANTS, listOf(EntityFilter.References)).refined(),
                TextProjection.None,
                SourceEntityLimit.parse(1).refined(),
                SourceTextByteLimit.parse(1_000).refined(),
                SourceReadPage.First,
                io.github.amichne.kast.kernel.ResourceBudget(
                    io.github.amichne.kast.kernel.ResultLimit.parse(1).refined(),
                    io.github.amichne.kast.kernel.WorkUnitLimit.parse(10).refined(),
                    io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(2_000).refined(),
                ),
            ),
            selector,
        )
    }

    private fun token() = SourceReadContinuation.parse("source-read-continuation-v1|" + "a".repeat(64)).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
