package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.amichne.kast.protocol.contract.SourceReadResult as ProtocolSourceReadResult
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult

class CanonicalSourceReadEntityProjectionTest {
    @Test
    fun `declaration entity projects reusable source parent and candidate selectors`() {
        val fixture = fixture()
        val authority = CanonicalProtocolAuthority()
        var captured: io.github.amichne.kast.source.contract.SourceReadRequest? = null
        val handler = CanonicalSourceReadHandler(
            SourceReadOperations { request ->
                captured = request
                fixture.result
            },
            authority,
        )

        val outcome = runSuspend {
            handler.execute(
                request(
                    SourceReadAnchorDocument.Source(text(fixture.regionToken.value)),
                    matchingDeclarations(),
                ),
            )
        } as OperationOutcome.Complete
        val result = outcome.evidence.payload as ProtocolSourceReadResult
        val declaration = result.entities.values.single() as SourceEntityDocument.Declaration
        val semantic = declaration.semanticIdentity as SourceDeclarationSemanticIdentityDocument.Candidate

        val projectedEntity = SourceSelectorTokenCodec.decode(
            SourceSelectorToken.parse(declaration.selection.selector.value).refined(),
        ).refined()
        val projectedParent = SourceSelectorTokenCodec.decode(
            SourceSelectorToken.parse(declaration.parentSelector.value).refined(),
        ).refined()
        assertEquals(fixture.entity.selector.fingerprint, projectedEntity.fingerprint)
        assertEquals(fixture.region.fingerprint, projectedParent.fingerprint)
        assertInstanceOf(CandidateSelectorLookup.Found::class.java, authority.candidate(semantic.selector))

        runSuspend {
            handler.execute(
                request(
                    SourceReadAnchorDocument.Source(declaration.selection.selector),
                    SourceEntitySelectionDocument.None,
                ),
            )
        }
        val sourceAnchor = captured?.anchor as SourceReadAnchor.Source
        assertEquals(fixture.entity.selector.fingerprint, sourceAnchor.selector.fingerprint)

        runSuspend {
            handler.execute(
                request(
                    SourceReadAnchorDocument.Candidate(semantic.selector),
                    SourceEntitySelectionDocument.None,
                ),
            )
        }
        val candidateAnchor = captured?.anchor as SourceReadAnchor.Candidate
        assertEquals(fixture.candidate.lease, candidateAnchor.selector.lease)
        assertTrue(candidateAnchor.selector is CandidateSelector.Declaration)
    }

    private fun request(
        anchor: SourceReadAnchorDocument,
        entities: SourceEntitySelectionDocument,
    ): SourceReadRequest = SourceReadRequest(
        anchor,
        SourceRegionSelectionDocument.Anchor,
        entities,
        SourceTextRequestDocument.None,
        SourceEntityLimitDocument.parse(250).refined(),
        SourceTextByteLimitDocument.parse(65_536).refined(),
        SourceReadPageDocument.First,
    )

    private fun matchingDeclarations(): SourceEntitySelectionDocument =
        SourceEntitySelectionDocument.Matching(
            SourceContainmentDocument.DIRECT,
            listOf(
                SourceEntityFilterDocument.Declarations(
                    listOf(SourceDeclarationKindDocument.FUNCTION),
                    SourceVisibilitySelectionDocument.Any,
                ),
            ),
        )

    private fun fixture(): Fixture {
        val source = "fun subject() = 1\n"
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/src/Subject.kt"),
        ).refined()
        val file = SymbolDiscoveryFileIdentity.Workspace(path)
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(17).refined())
        val snapshot = SourceSnapshot.create(
            lease,
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            file,
            SourceTextIdentity.fromNormalizedCommittedText(source),
            Utf16CodeUnitCount.parse(source.length).refined(),
        )
        val range = SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(0).refined(),
            Utf16CodeUnitOffset.parse(source.length).refined(),
        ).refined()
        val region = SourceSelector.issueRoot(range, SourceRegionKind.FILE)
        val selector = SourceSelector.issueEntity(
            region,
            NonEmptySourceRange.create(range).refined(),
            SourceEntityKind.DECLARATION_FUNCTION,
            SourceEntityName.present("subject").refined(),
        ).refined()
        val candidateFact = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "subject",
            lease,
            Path.of(path.value),
            Path.of(path.value).toUri().toString(),
            0,
        ).refined()
        val selection = SymbolDiscoverySelection.restore(
            lease,
            SymbolSearchScope.ExactFile(
                path,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            ),
            candidateFact,
        ).refined()
        val candidate = CandidateSelector.declaration(selection).refined()
        val entity = SourceEntity.Declaration.create(
            selector,
            SourceNestingDepth.parse(0).refined(),
            DeclarationKind.FUNCTION,
            DeclarationVisibility.PUBLIC,
            DeclarationSemanticIdentity.Candidate(candidate),
        ).refined()
        val result = DomainSourceReadResult.Complete.create(
            snapshot,
            SourceRegion.create(SourceRegionKind.FILE, region).refined(),
            listOf(entity),
            SourceTextProjection.NotRequested,
        ).refined()
        return Fixture(region, SourceSelectorTokenCodec.encode(region), candidate, entity, result)
    }

    private fun text(raw: String) =
        io.github.amichne.kast.protocol.contract.ProtocolText.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            },
        )
        return checkNotNull(completion).getOrThrow()
    }

    private data class Fixture(
        val region: SourceSelector,
        val regionToken: SourceSelectorToken,
        val candidate: CandidateSelector.Declaration,
        val entity: SourceEntity.Declaration,
        val result: DomainSourceReadResult.Complete,
    )
}
