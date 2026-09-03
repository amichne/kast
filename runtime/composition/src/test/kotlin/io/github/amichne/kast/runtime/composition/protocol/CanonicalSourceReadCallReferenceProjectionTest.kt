package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
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
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
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
import org.junit.jupiter.api.Test
import io.github.amichne.kast.protocol.contract.SourceReadResult as ProtocolSourceReadResult
import io.github.amichne.kast.source.contract.SourceReadResult as DomainSourceReadResult

class CanonicalSourceReadCallReferenceProjectionTest {
    @Test
    fun `call and reference targets project reusable source and exact selectors`() {
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
            handler.execute(request(SourceReadAnchorDocument.Source(text(fixture.regionToken.value))))
        } as OperationOutcome.Complete
        val result: ProtocolSourceReadResult = outcome.evidence.payload
        val call = result.entities.values[0] as SourceEntityDocument.Call
        val local = result.entities.values[1] as SourceEntityDocument.Reference
        val unresolved = result.entities.values[2] as SourceEntityDocument.Reference

        val callSelection = call.selection.selector.decodeSource()
        val calleeSelection = call.callee.selector.decodeSource() as SourceSelector.Entity
        assertEquals(fixture.call.selector.fingerprint, callSelection.fingerprint)
        assertEquals(callSelection.fingerprint, calleeSelection.parent.fingerprint)
        val symbolTarget = call.target as SourceEntityTargetDocument.Symbol
        assertInstanceOf(ExactSelectorLookup.Found::class.java, authority.exact(symbolTarget.selector))

        val localTarget = local.target as SourceEntityTargetDocument.Local
        assertEquals(
            fixture.localTarget.fingerprint,
            localTarget.selector.decodeSource().fingerprint,
        )
        assertEquals(
            SourceUnresolvedReasonDocument.NAME_NOT_FOUND,
            (unresolved.target as SourceEntityTargetDocument.Unresolved).reason,
        )

        runSuspend {
            handler.execute(request(SourceReadAnchorDocument.Source(call.selection.selector)))
        }
        val sourceAnchor = captured?.anchor as SourceReadAnchor.Source
        assertEquals(fixture.call.selector.fingerprint, sourceAnchor.selector.fingerprint)
    }

    private fun request(anchor: SourceReadAnchorDocument): SourceReadRequest = SourceReadRequest(
        anchor,
        SourceRegionSelectionDocument.Anchor,
        SourceEntitySelectionDocument.Matching(
            SourceContainmentDocument.DESCENDANTS,
            listOf(SourceEntityFilterDocument.Calls, SourceEntityFilterDocument.References),
        ),
        SourceTextRequestDocument.None,
        SourceEntityLimitDocument.parse(250).refined(),
        SourceTextByteLimitDocument.parse(65_536).refined(),
        SourceReadPageDocument.First,
    )

    private fun fixture(): Fixture {
        val source = "fun target() = Unit\nfun subject() { target(); local; missing }\n"
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
        val region = SourceSelector.issueRoot(
            range(snapshot, 0, source.length),
            SourceRegionKind.FILE,
        )
        val targetSelector = exactTarget(lease, path, file, source.indexOf('\n'))
        val callStart = source.indexOf("target()", source.indexOf("fun subject"))
        val callSelector = entity(
            region,
            range(snapshot, callStart, callStart + "target()".length),
            SourceEntityKind.CALL,
            "target",
        )
        val calleeSelector = entity(
            callSelector,
            range(snapshot, callStart, callStart + "target".length),
            SourceEntityKind.CALLEE,
            "target",
        )
        val call = SourceEntity.Call.create(
            callSelector,
            SourceNestingDepth.parse(0).refined(),
            calleeSelector,
            SourceEntityTarget.Symbol(targetSelector),
        ).refined()
        val localStart = source.indexOf("local")
        val localTarget = SourceSelector.issueRoot(
            range(snapshot, localStart, localStart + "local".length),
            SourceRegionKind.DECLARATION,
        )
        val local = SourceEntity.Reference.create(
            entity(
                region,
                range(snapshot, localStart, localStart + "local".length),
                SourceEntityKind.REFERENCE,
                "local",
            ),
            SourceNestingDepth.parse(0).refined(),
            SourceEntityTarget.Local(localTarget),
        ).refined()
        val missingStart = source.indexOf("missing")
        val unresolved = SourceEntity.Reference.create(
            entity(
                region,
                range(snapshot, missingStart, missingStart + "missing".length),
                SourceEntityKind.REFERENCE,
                "missing",
            ),
            SourceNestingDepth.parse(0).refined(),
            SourceEntityTarget.Unresolved(CompilerUnresolvedReason.NAME_NOT_FOUND),
        ).refined()
        val result = DomainSourceReadResult.Complete.create(
            snapshot,
            SourceRegion.create(SourceRegionKind.FILE, region).refined(),
            listOf(call, local, unresolved),
            SourceTextProjection.NotRequested,
        ).refined()
        return Fixture(
            region,
            SourceSelectorTokenCodec.encode(region),
            call,
            localTarget,
            result,
        )
    }

    private fun exactTarget(
        lease: SemanticReadLease,
        path: CanonicalWorkspaceFilePath,
        file: SymbolDiscoveryFileIdentity.Workspace,
        end: Int,
    ): SymbolSelector {
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file,
            0,
            end,
            "target",
            "sample.target",
            CompilerSymbolKind.FUNCTION,
            CanonicalCompilerSignature.function(
                "sample.target",
                null,
                emptyList(),
                emptyList(),
                0,
            ).refined(),
        ).refined()
        return SymbolSelector.issue(
            lease,
            SymbolSearchScope.ExactFile(
                path,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            ),
            evidence,
        )
    }

    private fun entity(
        parent: SourceSelector,
        range: SourceRange,
        kind: SourceEntityKind,
        name: String,
    ): SourceSelector.Entity = SourceSelector.issueEntity(
        parent,
        NonEmptySourceRange.create(range).refined(),
        kind,
        SourceEntityName.present(name).refined(),
    ).refined()

    private fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(start).refined(),
            Utf16CodeUnitOffset.parse(end).refined(),
        ).refined()

    private fun ProtocolText.decodeSource(): SourceSelector = SourceSelectorTokenCodec.decode(
        SourceSelectorToken.parse(value).refined(),
    ).refined()

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

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
        val call: SourceEntity.Call,
        val localTarget: SourceSelector,
        val result: DomainSourceReadResult.Complete,
    )
}
