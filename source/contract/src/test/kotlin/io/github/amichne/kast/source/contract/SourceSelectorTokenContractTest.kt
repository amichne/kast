package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceSelectorTokenContractTest {
    @Test
    fun `hierarchical selector token round trips every bound proof`() {
        val selector = entitySelector()
        val token = SourceSelectorTokenCodec.encode(selector)
        val decoded = SourceSelectorTokenCodec.decode(token).refined()
        val entity = assertIs<SourceSelector.Entity>(decoded)
        val body = assertIs<SourceSelector.NestedRegion>(entity.parent)
        val declaration = assertIs<SourceSelector.RootRegion>(body.parent)

        assertEquals(selector.fingerprint, entity.fingerprint)
        assertEquals(SourceEntityKind.REFERENCE, entity.kind)
        assertEquals("dependency", (entity.name as SourceEntityName.Present).value)
        assertEquals(SourceRegionKind.CALLABLE_BODY, body.kind)
        assertEquals(SourceRegionKind.DECLARATION, declaration.kind)
        assertEquals(selector.snapshot, decoded.snapshot)
    }

    @Test
    fun `token admission rejects digest semantic and structural tampering`() {
        val token = SourceSelectorTokenCodec.encode(entitySelector())
        val digestTampered = token.value.dropLast(1) +
            if (token.value.last() == '0') "1" else "0"
        assertEquals(
            SourceSelectorTokenFailure.PAYLOAD_DIGEST_MISMATCH,
            SourceSelectorTokenCodec.decode(SourceSelectorToken.parse(digestTampered).refined())
                .rejected(),
        )

        val semanticTamper = rewritePayload(token.value) { payload ->
            payload.replace("REFERENCE", "PARAMETER")
        }
        assertEquals(
            SourceSelectorTokenFailure.SELECTOR_REJECTED,
            SourceSelectorTokenCodec.decode(SourceSelectorToken.parse(semanticTamper).refined())
                .rejected(),
        )

        val trailingField = rewritePayload(token.value) { payload -> payload + "1:x" }
        assertEquals(
            SourceSelectorTokenFailure.MALFORMED_PAYLOAD,
            SourceSelectorTokenCodec.decode(SourceSelectorToken.parse(trailingField).refined())
                .rejected(),
        )
        assertIs<Refinement.Rejected<SourceSelectorTokenFailure>>(
            SourceSelectorToken.parse("candidate-selector-v2:not-source:${"0".repeat(64)}"),
        )
    }

    private fun entitySelector(): SourceSelector.Entity {
        val text = "fun subject() = dependency()\n"
        val snapshot = snapshot(text)
        val declaration = SourceSelector.issueRoot(
            range(snapshot, 0, text.length),
            SourceRegionKind.DECLARATION,
        )
        val body = SourceSelector.issueNested(
            declaration,
            range(snapshot, 16, 28),
            SourceRegionKind.CALLABLE_BODY,
        ).refined()
        val referenceRange = range(snapshot, 16, 26)
        return SourceSelector.issueEntity(
            body,
            NonEmptySourceRange.create(referenceRange).refined(),
            SourceEntityKind.REFERENCE,
            SourceEntityName.present("dependency").refined(),
        ).refined()
    }

    private fun snapshot(text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/kast")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/kast/src/main/kotlin/example/Subject.kt"),
        ).refined()
        return SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    private fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(start).refined(),
            Utf16CodeUnitOffset.parse(end).refined(),
        ).refined()

    private fun rewritePayload(raw: String, rewrite: (String) -> String): String {
        val parts = raw.split(':')
        val payload = Base64.getUrlDecoder().decode(parts[1]).toString(StandardCharsets.UTF_8)
        val rewritten = rewrite(payload).toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rewritten)
        val digest = MessageDigest.getInstance("SHA-256").digest(rewritten)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "${parts[0]}:$encoded:$digest"
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
