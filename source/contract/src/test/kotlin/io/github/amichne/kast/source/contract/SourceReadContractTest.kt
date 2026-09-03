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
import kotlin.test.assertIs

class SourceReadContractTest {
    @Test
    fun `request retains orthogonal region entity text and bound decisions`() {
        val root = rootSelector("fun subject() = dependency()\n")
        val declarations = DeclarationKindSelection.from(
            setOf(DeclarationKind.FUNCTION, DeclarationKind.PROPERTY),
        ).refined()
        val visibility = VisibilitySelection.exact(
            setOf(DeclarationVisibility.PUBLIC, DeclarationVisibility.INTERNAL),
        ).refined()
        val entities = EntitySelection.matching(
            containment = Containment.DESCENDANTS,
            filters = listOf(
                EntityFilter.Declarations(declarations, visibility),
                EntityFilter.Calls,
            ),
        ).refined()
        val window = TextProjection.window(lineCount(3), lineCount(5))
        val request = SourceReadRequest(
            anchor = SourceReadAnchor.Source(root),
            region = RegionSelection.Enclosing(EnclosingRegionKind.CALLABLE_BODY),
            entities = entities,
            text = window,
            entityLimit = entityLimit(250),
            textByteLimit = textByteLimit(65_536),
            page = SourceReadPage.First,
        )

        assertEquals(Containment.DESCENDANTS, entities.containment)
        assertEquals(
            listOf(DeclarationKind.FUNCTION, DeclarationKind.PROPERTY),
            declarations.values,
        )
        assertEquals(3, window.beforeLines.value)
        assertEquals(5, window.afterLines.value)
        assertIs<SourceReadAnchor.Source>(request.anchor)
    }

    @Test
    fun `invalid and contradictory request states reject during refinement`() {
        assertIs<Refinement.Rejected<DeclarationKindSelectionFailure>>(
            DeclarationKindSelection.from(emptySet()),
        )
        assertIs<Refinement.Rejected<VisibilitySelectionFailure>>(
            VisibilitySelection.exact(emptySet()),
        )
        assertIs<Refinement.Rejected<EntitySelectionFailure>>(
            EntitySelection.matching(Containment.DIRECT, emptyList()),
        )
        assertEquals(
            EntitySelectionFailure.DUPLICATE_FILTER,
            EntitySelection.matching(
                Containment.DIRECT,
                listOf(EntityFilter.Calls, EntityFilter.Calls),
            ).rejected(),
        )
        assertIs<Refinement.Rejected<LineCountFailure>>(LineCount.parse(-1))
        assertIs<Refinement.Rejected<LineCountFailure>>(LineCount.parse(1_001))
        assertIs<Refinement.Rejected<SourceEntityLimitFailure>>(SourceEntityLimit.parse(0))
        assertIs<Refinement.Rejected<SourceEntityLimitFailure>>(SourceEntityLimit.parse(1_001))
        assertIs<Refinement.Rejected<SourceTextByteLimitFailure>>(SourceTextByteLimit.parse(0))
    }

    @Test
    fun `complete evidence binds region entities and exact text to one snapshot`() {
        val text = "fun subject() = dependency()\n"
        val root = rootSelector(text)
        val region = SourceRegion.create(SourceRegionKind.DECLARATION, root).refined()
        val referenceRange = range(root.snapshot, 16, 26)
        val referenceSelector = SourceSelector.issueEntity(
            parent = root,
            range = NonEmptySourceRange.create(referenceRange).refined(),
            kind = SourceEntityKind.REFERENCE,
            name = SourceEntityName.present("dependency").refined(),
        ).refined()
        val reference = SourceEntity.Reference.create(
            selector = referenceSelector,
            nestingDepth = nestingDepth(0),
            target = SourceEntityTarget.Unresolved(CompilerUnresolvedReason.NAME_NOT_FOUND),
        ).refined()
        val projected = SourceTextProjection.returned(root, text).refined()

        val complete = SourceReadResult.Complete.create(
            snapshot = root.snapshot,
            region = region,
            entities = listOf(reference),
            text = projected,
        ).refined()

        assertEquals(text, (complete.text as SourceTextProjection.Returned).text)
        assertEquals(referenceSelector.fingerprint, complete.entities.single().selector.fingerprint)
        assertEquals(
            SourceTextProjectionFailure.TEXT_LENGTH_MISMATCH,
            SourceTextProjection.returned(root, text.dropLast(1)).rejected(),
        )
        assertEquals(
            SourceRegionFailure.KIND_MISMATCH,
            SourceRegion.create(SourceRegionKind.FILE, root).rejected(),
        )
    }

    @Test
    fun `qualification is non-empty and entity truncation carries continuation proof`() {
        val knownMinimum = SourceEntityCount.parse(250).refined()
        val continuation = SourceReadContinuation.parse(
            "source-read-continuation-v1|${"a".repeat(64)}",
        ).refined()

        assertEquals(
            SourceReadQualificationFailure.EMPTY_LIMITATIONS,
            SourceReadQualification.create(
                knownMinimum,
                emptySet(),
                SourceReadContinuationState.Unavailable,
            ).rejected(),
        )
        assertEquals(
            SourceReadQualificationFailure.CONTINUATION_REQUIRED,
            SourceReadQualification.create(
                knownMinimum,
                setOf(SourceReadLimitation.ENTITY_LIMIT_REACHED),
                SourceReadContinuationState.Unavailable,
            ).rejected(),
        )
        val qualification = SourceReadQualification.create(
            knownMinimum,
            setOf(
                SourceReadLimitation.ENTITY_LIMIT_REACHED,
                SourceReadLimitation.TIME_LIMIT_REACHED,
            ),
            SourceReadContinuationState.Available(continuation),
        ).refined()

        assertEquals(
            listOf(
                SourceReadLimitation.ENTITY_LIMIT_REACHED,
                SourceReadLimitation.TIME_LIMIT_REACHED,
            ),
            qualification.limitations,
        )
        assertEquals(SourceReadPage.Continue(continuation), SourceReadPage.Continue(continuation))
    }

    private fun rootSelector(text: String): SourceSelector.RootRegion {
        val snapshot = snapshot(text)
        return SourceSelector.issueRoot(
            range(snapshot, 0, snapshot.length.value),
            SourceRegionKind.DECLARATION,
        )
    }

    private fun snapshot(text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/kast")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/kast/src/main/kotlin/example/Subject.kt"),
        ).refined()
        return SourceSnapshot.create(
            lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined()),
            sourceState = WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            file = SymbolDiscoveryFileIdentity.Workspace(path),
            textIdentity = SourceTextIdentity.fromNormalizedCommittedText(text),
            length = Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    private fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(snapshot, offset(start), offset(end)).refined()

    private fun offset(raw: Int): Utf16CodeUnitOffset = Utf16CodeUnitOffset.parse(raw).refined()
    private fun lineCount(raw: Int): LineCount = LineCount.parse(raw).refined()
    private fun entityLimit(raw: Int): SourceEntityLimit = SourceEntityLimit.parse(raw).refined()
    private fun textByteLimit(raw: Long): SourceTextByteLimit =
        SourceTextByteLimit.parse(raw).refined()
    private fun nestingDepth(raw: Int): SourceNestingDepth =
        SourceNestingDepth.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
