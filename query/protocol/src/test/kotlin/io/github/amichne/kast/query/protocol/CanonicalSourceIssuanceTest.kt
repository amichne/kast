package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.source.contract.*
import io.github.amichne.kast.source.contract.SourceReadResult as DomainResult
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CanonicalSourceIssuanceTest {
    @Test fun `source declaration and repeated targets retain issued candidate transport`() = verifyIssuance(true)

    @Test fun `inline fallback and issuance rejection retain their existing semantics`() = verifyIssuance(false)

    private fun verifyIssuance(compact: Boolean) = runTest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).value()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).value())
        val path = Path.of("/workspace/Subject.kt")
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "café",
                    lease,
                    path,
                    path.toUri().toString(),
                    0,
                )
                .value()
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val selection = SymbolDiscoverySelection.restore(lease, scope, candidate).value()
        val declaration = CandidateSelector.declaration(selection).value()
        val source = "fun café() { café(); café(); local; missing }\n"
        val snapshot =
            SourceSnapshot.create(
                lease,
                WorkspaceStateIdentity.parse("state").value(),
                SymbolDiscoveryFileIdentity.Workspace(CanonicalWorkspaceFilePath.fromCanonicalPath(root, path).value()),
                SourceTextIdentity.fromNormalizedCommittedText(source),
                Utf16CodeUnitCount.parse(source.length).value(),
            )
        fun range(start: Int, end: Int) =
            SourceRange.create(
                    snapshot,
                    Utf16CodeUnitOffset.parse(start).value(),
                    Utf16CodeUnitOffset.parse(end).value(),
                )
                .value()
        val region = SourceSelector.issueRoot(range(0, source.length), SourceRegionKind.FILE)
        fun entity(parent: SourceSelector, start: Int, end: Int, kind: SourceEntityKind) =
            SourceSelector.issueEntity(
                    parent,
                    NonEmptySourceRange.create(range(start, end)).value(),
                    kind,
                    SourceEntityName.present("café").value(),
                )
                .value()
        val depth = SourceNestingDepth.parse(0).value()
        val decl = entity(region, 0, source.length - 1, SourceEntityKind.DECLARATION_FUNCTION)
        val entities = buildList {
            add(
                SourceEntity.Declaration.create(
                        decl,
                        depth,
                        DeclarationKind.FUNCTION,
                        DeclarationVisibility.PUBLIC,
                        DeclarationSemanticIdentity.Candidate(declaration),
                    )
                    .value()
            )
            for (start in listOf(13, 21)) {
                val call = entity(region, start, start + 6, SourceEntityKind.CALL)
                add(
                    SourceEntity.Call.create(
                            call,
                            depth,
                            entity(call, start, start + 4, SourceEntityKind.CALLEE),
                            SourceEntityTarget.Candidate(declaration),
                        )
                        .value()
                )
            }
            add(
                SourceEntity.Reference.create(
                        entity(region, 29, 34, SourceEntityKind.REFERENCE),
                        depth,
                        SourceEntityTarget.Local(decl),
                    )
                    .value()
            )
            add(
                SourceEntity.Reference.create(
                        entity(region, 36, 43, SourceEntityKind.REFERENCE),
                        depth,
                        SourceEntityTarget.Unresolved(CompilerUnresolvedReason.NAME_NOT_FOUND),
                    )
                    .value()
            )
        }
        val retained = mutableMapOf<ProtocolText, ProtocolText>()
        val references =
            CanonicalQueryReferences(
                object : QueryReferenceTransport {
                    override fun issue(canonical: ProtocolText): ProtocolText =
                        (if (compact) compactSymbolReference(canonical).token else canonical).also {
                            retained[it] = canonical
                        }

                    override fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> =
                        CanonicalSelectorDecoding.Decoded(retained[token] ?: token)
                }
            )
        val expected =
            (references.issueDeclarationCandidate(selection) as CandidateSelectorTokenIssuance.Issued).selector
        val operations = SourceReadOperations {
            DomainResult.Complete.create(
                    snapshot,
                    SourceRegion.create(SourceRegionKind.FILE, region).value(),
                    entities,
                    SourceTextProjection.returned(region, source).value(),
                )
                .value()
        }
        val protocol = CanonicalSourceReadProtocol(operations, references)
        val request =
            io.github.amichne.kast.protocol.contract.SourceReadRequest(
                SourceReadAnchorDocument.Candidate(expected),
                SourceRegionSelectionDocument.File,
                SourceEntitySelectionDocument.None,
                SourceTextRequestDocument.Complete,
                SourceEntityLimitDocument.parse(20).value(),
                SourceTextByteLimitDocument.parse(65536).value(),
                SourceReadPageDocument.First,
            )
        val budget =
            SourceProtocolBudget(
                ResourceBudget(
                    ResultLimit.parse(20).value(),
                    WorkUnitLimit.parse(1000).value(),
                    ElapsedTimeLimitMillis.parse(1000).value(),
                ),
                SourceTextByteLimit.parse(65536).value(),
            )
        val outcome = protocol.execute(request, lease, budget) as OperationOutcome.Complete
        val rejectingIssuer =
            object : QueryReferenceAuthority by references {
                override fun issueDeclarationCandidate(selection: SymbolDiscoverySelection) =
                    CandidateSelectorTokenIssuance.Rejected(CandidateSelectorTokenIssuanceFailure.TOKEN_REJECTED)
            }
        assertEquals(
            OperationOutcome.Rejected(io.github.amichne.kast.protocol.contract.SourceReadRejection.CONTRACT_VIOLATION),
            CanonicalSourceReadProtocol(operations, rejectingIssuer).execute(request, lease, budget),
        )
        val output = outcome.evidence.payload
        val identity =
            (output.entities.values.first() as SourceEntityDocument.Declaration).semanticIdentity
                as SourceDeclarationSemanticIdentityDocument.Candidate
        assertEquals(expected, identity.selector)
        output.entities.values.filterIsInstance<SourceEntityDocument.Call>().forEach {
            assertEquals(expected, (it.target as SourceEntityTargetDocument.Candidate).selector)
        }
        val restored =
            (references.restoreCandidate(identity.selector, lease) as CanonicalSelectorDecoding.Decoded).value
        val restoredSelection = (restored as CandidateSelector.Declaration).selection
        assertEquals(selection.lease, restoredSelection.lease)
        assertEquals(selection.scope, restoredSelection.scope)
        assertEquals(selection.constraints, restoredSelection.constraints)
        assertEquals(selection.candidate.location, restoredSelection.candidate.location)
        val refs = output.entities.values.filterIsInstance<SourceEntityDocument.Reference>()
        assertEquals(
            SourceSelectorTokenCodec.encode(decl).value,
            (refs.first().target as SourceEntityTargetDocument.Local).selector.value,
        )
        assertEquals(
            SourceUnresolvedReasonDocument.NAME_NOT_FOUND,
            (refs.last().target as SourceEntityTargetDocument.Unresolved).reason,
        )
        assertTrue(references.restoreExact(expected, lease) is CanonicalSelectorDecoding.Rejected)
        assertEquals(source, (output.text as SourceTextProjectionDocument.Returned).text.value)
    }

    private fun <T, E> Refinement<T, E>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
