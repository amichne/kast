package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceSelectorTokenContractTest {
    @Test
    fun `modeled source references restore only against matching current owner evidence`() {
        val previous = snapshot("fun subject() = dependency()\n")
        val model = model()
        val owner = model.sourceRoots.single()
        val scopes = listOf(
            SymbolSearchScope.Module(owner.module, SymbolSourceKindPolicy.PRODUCTION_ONLY, SymbolGeneratedSourcePolicy.EXCLUDE),
            SymbolSearchScope.SourceSet(owner.project, owner.sourceSet, SymbolSourceKindPolicy.PRODUCTION_ONLY, SymbolGeneratedSourcePolicy.EXCLUDE),
            SymbolSearchScope.GradleProject(owner.project, SymbolSourceKindPolicy.PRODUCTION_ONLY, SymbolGeneratedSourcePolicy.EXCLUDE),
        )
        for (scope in scopes) {
            val constrained = SourceReadScope.Constrained(scope, SymbolDiscoveryConstraints.None)
            val scoped = SourceSnapshot.create(previous.context, previous.file, previous.textIdentity, previous.length, constrained)
            val selector = SourceSelector.issueRoot(range(scoped, 0, scoped.length.value), SourceRegionKind.FILE)
            val token = SourceSelectorTokenCodec.encode(selector)

            assertEquals(SourceSelectorTokenFailure.SNAPSHOT_REJECTED, SourceSelectorTokenCodec.decode(token).rejected())
            assertEquals(constrained, SourceSelectorTokenCodec.decode(token, model).refined().snapshot.readScope)
            assertEquals(selector.fingerprint, SourceSelectorTokenCodec.decode(token, model).refined().fingerprint)
            assertEquals(SourceSelectorTokenFailure.SNAPSHOT_REJECTED,
                SourceSelectorTokenCodec.decode(token, model(owner = "other")).rejected())
            assertEquals(SourceSelectorTokenFailure.SNAPSHOT_REJECTED,
                SourceSelectorTokenCodec.decode(token, model(root = "/foreign/kast")).rejected())
        }
    }

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

    @Test
    fun `source reference preserves named source sets and directory scope`() {
        val previous = snapshot("fun subject() = dependency()\n")
        val scope = SourceReadScope.Constrained(
            SymbolSearchScope.Workspace(SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE, SymbolLibraryPolicy.EXCLUDE),
            SymbolDiscoveryConstraints(
                SymbolDiscoveryDirectoryConstraint(SymbolDiscoveryDirectory.parse("src").refined(), SymbolDiscoveryContainment.DESCENDANTS),
                null,
                sourceSets = SymbolDiscoverySourceSets.Exact.from(setOf(
                    io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName.parse("main").refined(),
                )).refined(),
            ),
        )
        val scoped = SourceSnapshot.create(previous.context, previous.file, previous.textIdentity, previous.length, scope)
        val selector = SourceSelector.issueRoot(range(scoped, 0, scoped.length.value), SourceRegionKind.FILE)
        val token = SourceSelectorTokenCodec.encode(selector)
        kotlin.test.assertTrue(token.value.startsWith("source-selector-v2:"))
        val restored = SourceSelectorTokenCodec.decode(token).refined()
        assertEquals(scope, restored.snapshot.readScope)
        assertEquals(selector.fingerprint, restored.fingerprint)
        assertEquals(SourceSelectorRevalidationFailure.SOURCE_SCOPE_MISMATCH,
            RevalidatedSourceSelector.validate(selector, previous).rejected())
        val tampered = rewritePayload(token.value) { it.replace("4:main", "4:test") }
        assertEquals(SourceSelectorTokenFailure.SELECTOR_REJECTED,
            SourceSelectorTokenCodec.decode(SourceSelectorToken.parse(tampered).refined()).rejected())
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

    private fun model(root: String = "/workspace/kast", owner: String = "main"): WorkspaceSearchScopeModel =
        assertIs<WorkspaceSearchScopeModelCompilation.Compiled>(WorkspaceSearchScopeModel.compile(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(root)).refined(),
            ImportedWorkspaceModelState.COMPLETE,
            listOf(WorkspaceSourceRootBoundary(owner, Path.of(root), ":$owner", owner,
                Path.of(root, "src/main/kotlin"), WorkspaceSourceRootKind.PRODUCTION, WorkspaceSourceRootProvenance.AUTHORED)),
        )).model

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
