package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceDeclarationSemanticIdentityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityTargetDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SourceUnresolvedReasonDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.compactSourceDocument
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadResult as DomainResult
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
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
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalSourceIssuanceTest {
    @Test fun `source declaration and repeated targets retain issued candidate transport`() = verifyIssuance(true)

    @Test fun `inline fallback and issuance rejection retain their existing semantics`() = verifyIssuance(false)

    @Test
    fun `source retains every exact authority rejection without executing provider in either format`() = runTest {
        val fixture = SourceIssuanceFixture()
        val references = fixture.references(true)
        val token =
            (references.issueDeclarationCandidate(fixture.selection) as CandidateSelectorTokenIssuance.Issued).selector
        for (format in SourceReadFormatDocument.entries) for (failure in CanonicalSelectorDecodingFailure.entries) {
            var executed = false
            val rejecting =
                object : QueryReferenceAuthority by references {
                    override fun restoreCandidate(
                        token: ProtocolText,
                        current: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
                    ): CanonicalSelectorDecoding<CandidateSelector> = CanonicalSelectorDecoding.Rejected(failure)
                }
            val result =
                CanonicalSourceReadProtocol(
                        SourceReadOperations {
                            executed = true
                            error("Rejected reference reached source")
                        },
                        rejecting,
                    )
                    .execute(fixture.request(token).copy(format = format), fixture.lease, fixture.budget)
                    as OperationOutcome.Rejected
            val cause =
                result.reason as io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.ReferenceRejected
            assertEquals(io.github.amichne.kast.protocol.contract.SourceReferenceRole.CANDIDATE, cause.role)
            assertEquals(failure.sourceFailure(), cause.reason)
            assertTrue(!executed)
            val encoded =
                (CanonicalOperationWireBindings.sourceRead.encodeOutcome(result) as WireEncoding.Encoded).document
            assertEquals(WireDecoding.Decoded(result), CanonicalOperationWireBindings.sourceRead.decodeOutcome(encoded))
            assertTrue(!encoded.contains(token.value))
        }
    }

    private fun verifyIssuance(compact: Boolean) = runTest {
        val fixture = SourceIssuanceFixture()
        val references = fixture.references(compact)
        val expected =
            (references.issueDeclarationCandidate(fixture.selection) as CandidateSelectorTokenIssuance.Issued).selector
        val operations = fixture.operations()
        val request = fixture.request(expected)
        val outcome =
            CanonicalSourceReadProtocol(operations, references).execute(request, fixture.lease, fixture.budget)
                as OperationOutcome.Complete
        val rejectingIssuer =
            object : QueryReferenceAuthority by references {
                override fun issueDeclarationCandidate(selection: SymbolDiscoverySelection) =
                    CandidateSelectorTokenIssuance.Rejected(CandidateSelectorTokenIssuanceFailure.TOKEN_REJECTED)
            }
        assertEquals(
            OperationOutcome.Rejected(
                io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.InternalContractFailure(
                    io.github.amichne.kast.protocol.contract.SourceInternalObligation.RESULT_PROJECTION
                )
            ),
            CanonicalSourceReadProtocol(operations, rejectingIssuer).execute(request, fixture.lease, fixture.budget),
        )
        fixture.verify(outcome.evidence.payload, references, expected)
        fixture.verifyCompact(references, expected, compact)
    }
}

private class SourceIssuanceFixture {
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

    private fun range(start: Int, end: Int) =
        SourceRange.create(
                snapshot,
                Utf16CodeUnitOffset.parse(start).value(),
                Utf16CodeUnitOffset.parse(end).value(),
            )
            .value()

    val region = SourceSelector.issueRoot(range(0, source.length), SourceRegionKind.FILE)

    private fun entity(parent: SourceSelector, start: Int, end: Int, kind: SourceEntityKind) =
        SourceSelector.issueEntity(
                parent,
                NonEmptySourceRange.create(range(start, end)).value(),
                kind,
                SourceEntityName.present("café").value(),
            )
            .value()

    val depth = SourceNestingDepth.parse(0).value()
    val decl = entity(region, 0, source.length - 1, SourceEntityKind.DECLARATION_FUNCTION)

    fun entities() = buildList {
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

    fun references(compact: Boolean): CanonicalQueryReferences {
        val retained = mutableMapOf<ProtocolText, ProtocolText>()
        return CanonicalQueryReferences(
            object : QueryReferenceTransport {
                override fun issue(canonical: ProtocolText): ProtocolText =
                    (if (compact) compactSymbolReference(canonical).token else canonical).also {
                        retained[it] = canonical
                    }

                override fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> =
                    CanonicalSelectorDecoding.Decoded(retained[token] ?: token)
            }
        )
    }

    fun operations() = SourceReadOperations {
        DomainResult.Complete.create(
                snapshot,
                SourceRegion.create(SourceRegionKind.FILE, region).value(),
                entities(),
                SourceTextProjection.returned(region, source).value(),
            )
            .value()
    }

    fun request(expected: ProtocolText) =
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

    suspend fun verifyCompact(references: CanonicalQueryReferences, expected: ProtocolText, compact: Boolean) {
        val protocol = CanonicalSourceReadProtocol(operations(), references)
        val expanded = protocol.execute(request(expected), lease, budget) as OperationOutcome.Complete
        val compactOutcome =
            protocol.execute(request(expected).copy(format = SourceReadFormatDocument.COMPACT), lease, budget)
                as OperationOutcome.Complete
        val binding = CanonicalOperationWireBindings.sourceRead
        val expandedEncoded = (binding.encodeOutcome(expanded) as WireEncoding.Encoded).document
        val compactEncoded = (binding.encodeOutcome(compactOutcome) as WireEncoding.Encoded).document
        println(
            "source-fixture transport=$compact expanded=${expandedEncoded.toByteArray(Charsets.UTF_8).size}" +
                " compact=${compactEncoded.toByteArray(Charsets.UTF_8).size}"
        )
        assertTrue(compactEncoded.toByteArray(Charsets.UTF_8).size < expandedEncoded.toByteArray(Charsets.UTF_8).size)
        val decoded = (binding.decodeOutcome(compactEncoded) as WireDecoding.Decoded).value as OperationOutcome.Complete
        assertEquals(
            expanded.evidence,
            decoded.evidence.copy(payload = decoded.evidence.payload.copy(format = SourceReadFormatDocument.EXPANDED)),
        )
        val table = compactOutcome.evidence.payload.compactSourceDocument().selections
        assertEquals(table.distinct(), table)
        assertTrue(SourceSelectorToken.parse("0") is Refinement.Rejected)
        for (entry in table) {
            val token = SourceSelectorToken.parse(entry.selector).value()
            assertTrue(references.restoreSource(token, lease) is Refinement.Refined)
        }
        val directory = Files.createDirectories(Path.of("build/reports/source-fixture"))
        Files.writeString(directory.resolve("expanded-$compact.json"), expandedEncoded)
        Files.writeString(directory.resolve("compact-$compact.json"), compactEncoded)
    }

    fun verify(
        output: io.github.amichne.kast.protocol.contract.SourceReadResult,
        references: CanonicalQueryReferences,
        expected: ProtocolText,
    ) {
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
}

private fun <T, E> Refinement<T, E>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected: $failure")
    }
