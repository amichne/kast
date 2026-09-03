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
import kotlin.test.assertNotEquals

class SourceSelectorContractTest {
    @Test
    fun `committed document identity and ranges use exact UTF-16 code units`() {
        val normalizedText = "// 😀\nfun x() = 1\n"
        val crlfText = normalizedText.replace("\n", "\r\n")
        val snapshot = snapshot(normalizedText)

        assertEquals(18, snapshot.length.value)
        assertEquals(
            "intellij-document-utf16be-sha256-v1|" +
                "d2273357b334bb5e213083ac8bf0a01020155b277ebb25b860882aeea0d5e521",
            snapshot.textIdentity.value,
        )
        assertNotEquals(
            snapshot.textIdentity,
            SourceTextIdentity.fromNormalizedCommittedText(crlfText),
        )

        val functionKeyword = SourceRange.create(
            snapshot,
            offset(6),
            offset(9),
        ).refined()
        assertEquals("fun", normalizedText.substring(
            functionKeyword.startInclusive.value,
            functionKeyword.endExclusive.value,
        ))

        assertIs<Refinement.Rejected<SourceRangeFailure>>(
            SourceRange.create(snapshot, offset(9), offset(6)),
        )
        assertIs<Refinement.Rejected<SourceRangeFailure>>(
            SourceRange.create(snapshot, offset(0), offset(19)),
        )
        val empty = SourceRange.create(snapshot, offset(6), offset(6)).refined()
        assertIs<Refinement.Rejected<NonEmptySourceRangeFailure>>(
            NonEmptySourceRange.create(empty),
        )

        val emptySnapshot = snapshot("")
        assertIs<Refinement.Refined<SourceRange>>(
            SourceRange.create(emptySnapshot, offset(0), offset(0)),
        )
    }

    @Test
    fun `nested selectors are snapshot bounded and bind their exact parent`() {
        val snapshot = snapshot("fun x() = value\n")
        val declarationRange = range(snapshot, 0, snapshot.length.value)
        val declaration = SourceSelector.issueRoot(
            declarationRange,
            SourceRegionKind.DECLARATION,
        )
        val bodyRange = range(snapshot, 10, 15)
        val body = SourceSelector.issueNested(
            parent = declaration,
            range = bodyRange,
            kind = SourceRegionKind.CALLABLE_BODY,
        ).refined()
        val reference = SourceSelector.issueEntity(
            parent = body,
            range = NonEmptySourceRange.create(bodyRange).refined(),
            kind = SourceEntityKind.REFERENCE,
            name = SourceEntityName.present("value").refined(),
        ).refined()

        assertEquals(body.fingerprint, reference.parent.fingerprint)
        assertEquals(declaration.fingerprint, body.parent.fingerprint)

        val alternateParent = SourceSelector.issueRoot(
            declarationRange,
            SourceRegionKind.FILE,
        )
        assertIs<Refinement.Rejected<SourceSelectorIssueFailure>>(
            SourceSelector.restoreNested(
                parent = alternateParent,
                range = body.range,
                kind = body.kind,
                fingerprint = body.fingerprint,
            ),
        )

        val tampered = SourceSelectorFingerprint.parse("0".repeat(64)).refined()
        assertIs<Refinement.Rejected<SourceSelectorIssueFailure>>(
            SourceSelector.restoreRoot(
                range = declaration.range,
                kind = declaration.kind,
                fingerprint = tampered,
            ),
        )

        val otherSnapshot = snapshot("fun y() = value\n")
        assertIs<Refinement.Rejected<SourceSelectorIssueFailure>>(
            SourceSelector.issueNested(
                parent = declaration,
                range = range(otherSnapshot, 10, 15),
                kind = SourceRegionKind.CALLABLE_BODY,
            ),
        )
    }

    @Test
    fun `selector revalidation rejects each moved snapshot identity`() {
        val text = "fun x() = 1\n"
        val issuedSnapshot = snapshot(text)
        val selector = SourceSelector.issueRoot(
            range(issuedSnapshot, 0, issuedSnapshot.length.value),
            SourceRegionKind.DECLARATION,
        )

        assertIs<Refinement.Refined<RevalidatedSourceSelector>>(
            RevalidatedSourceSelector.validate(selector, issuedSnapshot),
        )
        assertEquals(
            SourceSelectorRevalidationFailure.STALE_GENERATION,
            RevalidatedSourceSelector.validate(
                selector,
                snapshot(text, generation = 8),
            ).rejected(),
        )
        assertEquals(
            SourceSelectorRevalidationFailure.SOURCE_STATE_MISMATCH,
            RevalidatedSourceSelector.validate(
                selector,
                snapshot(text, sourceState = "workspace-state-v1|moved"),
            ).rejected(),
        )
        assertEquals(
            SourceSelectorRevalidationFailure.DOCUMENT_IDENTITY_MISMATCH,
            RevalidatedSourceSelector.validate(selector, snapshot("fun x() = 2\n")).rejected(),
        )
    }

    private fun snapshot(
        text: String,
        generation: Long = 7,
        sourceState: String = "workspace-state-v1|source",
    ): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/kast")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/kast/src/main/kotlin/example/Subject.kt"),
        ).refined()
        return SourceSnapshot.create(
            lease = SemanticReadLease(root, EvidenceGeneration.parse(generation).refined()),
            sourceState = WorkspaceStateIdentity.parse(sourceState).refined(),
            file = SymbolDiscoveryFileIdentity.Workspace(path),
            textIdentity = SourceTextIdentity.fromNormalizedCommittedText(text),
            length = Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    private fun range(
        snapshot: SourceSnapshot,
        startInclusive: Int,
        endExclusive: Int,
    ): SourceRange = SourceRange.create(
        snapshot,
        offset(startInclusive),
        offset(endExclusive),
    ).refined()

    private fun offset(raw: Int): Utf16CodeUnitOffset =
        Utf16CodeUnitOffset.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
