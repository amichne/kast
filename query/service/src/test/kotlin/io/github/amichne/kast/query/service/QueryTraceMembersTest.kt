package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.query.contract.QueryTracePhase
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityCount
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadQualification
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Source and compiler observations are scripted; actual source PSI/K2 qualification is a separate boundary. */
class QueryTraceMembersTest {
    @Test
    fun `interface trace includes direct method when no interface type reference reaches its callers`() = runTest {
        val f = Fixture()
        var sourceReads = 0
        val read = SourceReadOperations { request ->
            sourceReads += 1
            assertEquals(SourceReadAnchor.Symbol(f.owner), request.anchor)
            traceMemberSourceRead(f.owner, listOf("execute"))
        }
        val relations =
            io.github.amichne.kast.relation.contract.RelationOperations { request ->
                val batch =
                    io.github.amichne.kast.relation.contract.RelationBatch.create(
                            request,
                            emptyList(),
                            io.github.amichne.kast.relation.contract.RelationByteCount.parse(0).refined(),
                            io.github.amichne.kast.relation.contract.RelationWorkCount.parse(0).refined(),
                            io.github.amichne.kast.relation.contract.RelationResultCount.parse(0).refined(),
                        )
                        .refined()
                val completed = io.github.amichne.kast.relation.contract.RelationCompilation.complete(batch)
                io.github.amichne.kast.relation.contract.RelationReadResult.Complete(
                    completed.batch,
                    completed.coverage,
                )
            }
        val service =
            QueryService(
                f.fixture.discoveryEmpty(false),
                f.compiler(),
                read,
                relations,
                unexpectedQueryTraversal(),
                queryTestTraversalCeiling(),
            )
        val plan = f.fixture.exactReferencePlan(listOf(f.owner), listOf(QueryStepSyntax.Trace()))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(f.fixture.request(plan, 100L, resultLimit = 100)),
            )
        assertEquals(
            setOf("PaymentService", "execute"),
            result.result.symbolRows().map { it.description.name.value }.toSet(),
        )
        assertEquals(
            1,
            sourceReads,
            "Only the class seed supplies members; method traces must not recurse through source enumeration",
        )
    }

    @Test
    fun `direct interface member reaches method trace without root class kind filtering`() = runTest {
        val f = Fixture()
        val page = traceMemberSourceRead(f.owner, listOf("execute"))
        val read = SourceReadOperations { request ->
            assertEquals(SourceReadAnchor.Symbol(f.owner), request.anchor)
            assertEquals(RegionSelection.Anchor, request.region)
            assertEquals(TextProjection.None, request.text)
            val selected = request.entities as EntitySelection.Matching
            assertEquals(Containment.DIRECT, selected.containment)
            assertEquals(
                setOf(DeclarationKind.FUNCTION, DeclarationKind.PROPERTY),
                (selected.filters.single() as EntityFilter.Declarations).kinds.values.toSet(),
            )
            page
        }
        val members = f.members(read)
        val tasks = ArrayDeque<PipelineTask>(listOf(f.task))
        assertTrue(members.advance(f.task, f.state, tasks))
        val candidate = assertInstanceOf(PipelineTask.TraceMember::class.java, tasks.first())
        assertTrue(members.advance(candidate, f.state, tasks))
        val traced = assertInstanceOf(PipelineTask.Symbol::class.java, tasks.single())
        assertEquals("execute", traced.value.description.name.value)
        assertEquals(CompilerSymbolKind.FUNCTION, traced.value.description.kind)
        assertEquals(QueryTracePhase.SEED, (traced.stage as ExactQueryStage.Trace).phase)
        assertEquals(f.stage.expansion, (traced.stage as ExactQueryStage.Trace).expansion)
        assertEquals(
            51L,
            f.state.consumedWork().value,
            "One source ceiling plus one refinement stays inside the shared 100-unit grant",
        )
    }

    @Test
    fun `nested owner returned as direct member is rejected before compiler effects`() = runTest {
        val f = Fixture()
        val good = traceMemberSourceRead(f.owner, listOf("execute"))
        val entity = good.entities.single() as SourceEntity.Declaration
        val nested =
            SourceSelector.issueNested(good.region.selector, entity.selector.range, SourceRegionKind.CLASS_BODY)
                .refined()
        val selector =
            SourceSelector.issueEntity(
                    nested,
                    NonEmptySourceRange.create(entity.selector.range).refined(),
                    entity.selector.kind,
                    entity.selector.name,
                )
                .refined()
        val wrong =
            SourceEntity.Declaration.create(
                    selector,
                    entity.nestingDepth,
                    entity.kind,
                    entity.visibility,
                    entity.semanticIdentity,
                )
                .refined()
        val page =
            SourceReadResult.Complete.create(
                    good.snapshot,
                    good.region,
                    listOf(wrong),
                    SourceTextProjection.NotRequested,
                )
                .refined()
        val members = f.members(SourceReadOperations { page }, rejectRefinement = true)
        val tasks = ArrayDeque<PipelineTask>(listOf(f.task))
        assertTrue(members.advance(f.task, f.state, tasks))
        assertTrue(tasks.isEmpty())
        assertTrue(QueryLimitation.SOURCE_INCOMPLETE in f.state.limitations)
        val failure = assertInstanceOf(QueryItemFailure.Source::class.java, f.state.drainFailures().single())
        assertEquals(QuerySourceFailure.Rejected(SourceReadRejection.CONTRACT_VIOLATION), failure.reason)
    }

    @Test
    fun `source pagination retains exact cursor and discharges recoverable qualification after completion`() = runTest {
        val f = Fixture()
        val page = traceMemberSourceRead(f.owner, emptyList())
        val cursor = SourceReadContinuation.parse("source-read-continuation-v1|" + "a".repeat(64)).refined()
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCount.parse(0).refined(),
                    setOf(SourceReadLimitation.ENTITY_LIMIT_REACHED),
                    SourceReadContinuationState.Available(cursor),
                )
                .refined()
        var calls = 0
        val members =
            f.members(
                SourceReadOperations { request ->
                    calls += 1
                    when (calls) {
                        1 -> {
                            assertEquals(SourceReadPage.First, request.page)
                            SourceReadResult.Qualified.create(
                                    page.snapshot,
                                    page.region,
                                    emptyList(),
                                    SourceTextProjection.NotRequested,
                                    qualification,
                                )
                                .refined()
                        }
                        2 -> {
                            assertEquals(SourceReadPage.Continue(cursor), request.page)
                            page
                        }
                        else -> error("Unexpected source page")
                    }
                }
            )
        val tasks = ArrayDeque<PipelineTask>(listOf(f.task))
        assertTrue(members.advance(f.task, f.state, tasks))
        val continued = assertInstanceOf(PipelineTask.TraceMembers::class.java, tasks.single())
        assertSame(qualification, continued.qualification)
        assertFalse(QueryLimitation.SOURCE_INCOMPLETE in f.state.limitations)
        assertTrue(members.advance(continued, f.state, tasks))
        assertTrue(tasks.isEmpty())
        assertEquals(2, calls)
        assertTrue(f.state.drainFailures().isEmpty())
    }

    @Test
    fun `repeated source cursor rejects continuation while preserving proven page members`() = runTest {
        val f = Fixture()
        val page = traceMemberSourceRead(f.owner, listOf("execute"))
        val cursor = SourceReadContinuation.parse("source-read-continuation-v1|" + "b".repeat(64)).refined()
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCount.parse(1).refined(),
                    setOf(SourceReadLimitation.ENTITY_LIMIT_REACHED),
                    SourceReadContinuationState.Available(cursor),
                )
                .refined()
        val partial =
            SourceReadResult.Qualified.create(
                    page.snapshot,
                    page.region,
                    page.entities,
                    SourceTextProjection.NotRequested,
                    qualification,
                )
                .refined()
        val task = f.task.copy(page = SourceReadPage.Continue(cursor), qualification = qualification)
        val tasks = ArrayDeque<PipelineTask>(listOf(task))
        f.members(SourceReadOperations { partial }).advance(task, f.state, tasks)
        assertInstanceOf(PipelineTask.TraceMember::class.java, tasks.single())
        assertTrue(QueryLimitation.SOURCE_INCOMPLETE in f.state.limitations)
        val failure = assertInstanceOf(QueryItemFailure.Source::class.java, f.state.drainFailures().single())
        assertEquals(QuerySourceFailure.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH), failure.reason)
    }

    @Test
    fun `terminal source qualifier retains finite original limitations without manufacturing rejection`() = runTest {
        val f = Fixture()
        val page = traceMemberSourceRead(f.owner, emptyList())
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCount.parse(0).refined(),
                    setOf(SourceReadLimitation.UNSUPPORTED_ENTITY),
                    SourceReadContinuationState.Unavailable,
                )
                .refined()
        val partial =
            SourceReadResult.Qualified.create(
                    page.snapshot,
                    page.region,
                    emptyList(),
                    SourceTextProjection.NotRequested,
                    qualification,
                )
                .refined()
        val tasks = ArrayDeque<PipelineTask>(listOf(f.task))
        f.members(SourceReadOperations { partial }).advance(f.task, f.state, tasks)
        assertTrue(QueryLimitation.SOURCE_INCOMPLETE in f.state.limitations)
        val failure = assertInstanceOf(QueryItemFailure.Source::class.java, f.state.drainFailures().single())
        assertSame(qualification, (failure.reason as QuerySourceFailure.EnumerationIncomplete).qualification)
    }

    @Test
    fun `failed compiler refinement preserves its finite failure and produces no member trace`() = runTest {
        val f = Fixture()
        val tasks = ArrayDeque<PipelineTask>(listOf(f.task))
        val members =
            f.members(
                SourceReadOperations { traceMemberSourceRead(f.owner, listOf("execute")) },
                failure = SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE,
            )
        members.advance(f.task, f.state, tasks)
        members.advance(tasks.first() as PipelineTask.TraceMember, f.state, tasks)
        assertTrue(tasks.isEmpty())
        assertTrue(QueryLimitation.REFINEMENT_INCOMPLETE in f.state.limitations)
        val failure = assertInstanceOf(QueryItemFailure.Refinement::class.java, f.state.drainFailures().single())
        assertEquals(SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE, failure.reason)
    }

    private class Fixture {
        val fixture = QueryServiceTest()
        val owner = fixture.selector(fixture.selection())
        val stage =
            ExactQueryStage.Trace(
                QueryTracePhase.SEED,
                io.github.amichne.kast.relation.contract.RelationSearchBoundary.WORKSPACE_EXPANSION,
                ExactQueryStage.Distinct(
                    ExactQueryStage.Emit(QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()))
                ),
            )
        val task = PipelineTask.TraceMembers(QuerySymbol(SymbolDescription.from(owner), emptyList()), stage)
        val plan = fixture.symbolPlan()
        val state = QueryExecutionState(fixture.request(plan, 100L), QueryNanoClock { 0L })

        fun compiler(rejectRefinement: Boolean = false, failure: SymbolExactRejection? = null): SymbolExactOperations =
            fixture.exactOperations(
                describe = { SymbolDescriptionResult.Described(SymbolDescription.from(it)) },
                resolve = { selected ->
                    check(!rejectRefinement) { "Wrong ownership must reject before compiler effects" }
                    if (failure != null) SymbolResolutionResult.Rejected(failure)
                    else {
                        val location = selected.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
                        val signature =
                            CanonicalCompilerSignature.function(
                                    "sample.PaymentService.execute",
                                    null,
                                    emptyList(),
                                    emptyList(),
                                    0,
                                )
                                .refined()
                        val evidence =
                            CompilerGroundedSymbolEvidence.fromBoundary(
                                    location.file,
                                    location.offset.value,
                                    location.offset.value + 4,
                                    selected.candidate.name.value,
                                    "sample.PaymentService.execute",
                                    CompilerSymbolKind.FUNCTION,
                                    signature,
                                )
                                .refined()
                        SymbolResolutionResult.Resolved(
                            ResolvedSymbol(SymbolSelector.issue(selected, evidence).refined())
                        )
                    }
                },
            )

        fun members(
            source: SourceReadOperations,
            rejectRefinement: Boolean = false,
            failure: SymbolExactRejection? = null,
        ): QueryTraceMembers =
            QueryTraceMembers(
                source,
                QueryReadStages(fixture.discoveryEmpty(false), compiler(rejectRefinement, failure), source),
            )
    }
}

internal fun traceMemberSourceRead(owner: SymbolSelector, names: List<String>): SourceReadResult.Complete {
    val snapshot =
        SourceSnapshot.create(
            SourceReadContext.Published(
                owner.lease as SemanticReadLease,
                WorkspaceStateIdentity.parse("a".repeat(64)).refined(),
            ),
            owner.file as SymbolDiscoveryFileIdentity.Workspace,
            SourceTextIdentity.fromNormalizedCommittedText(" ".repeat(128)),
            Utf16CodeUnitCount.parse(128).refined(),
            SourceReadScope.Constrained(owner.scope, owner.constraints),
        )
    fun range(start: Int, end: Int) =
        SourceRange.create(
                snapshot,
                Utf16CodeUnitOffset.parse(start).refined(),
                Utf16CodeUnitOffset.parse(end).refined(),
            )
            .refined()
    val anchor =
        SourceSelector.issueRoot(
            range(owner.range.startInclusive, owner.range.endExclusive),
            SourceRegionKind.DECLARATION,
        )
    val entities = names.mapIndexed { index, name -> traceMemberEntity(owner, snapshot, anchor, index, name) }
    return SourceReadResult.Complete.create(
            snapshot,
            SourceRegion.create(SourceRegionKind.DECLARATION, anchor).refined(),
            entities,
            SourceTextProjection.NotRequested,
        )
        .refined()
}

private fun traceMemberEntity(
    owner: SymbolSelector,
    snapshot: SourceSnapshot,
    anchor: SourceSelector,
    index: Int,
    name: String,
): SourceEntity.Declaration {
    val offset = owner.range.startInclusive + 2 + index * 5
    val selector =
        SourceSelector.issueEntity(
                anchor,
                NonEmptySourceRange.create(
                        SourceRange.create(
                                snapshot,
                                Utf16CodeUnitOffset.parse(offset).refined(),
                                Utf16CodeUnitOffset.parse(offset + 4).refined(),
                            )
                            .refined()
                    )
                    .refined(),
                SourceEntityKind.DECLARATION_FUNCTION,
                SourceEntityName.present(name).refined(),
            )
            .refined()
    val selection = traceMemberSelection(owner, name, offset)
    return SourceEntity.Declaration.create(
            selector,
            SourceNestingDepth.parse(0).refined(),
            DeclarationKind.FUNCTION,
            DeclarationVisibility.PUBLIC,
            DeclarationSemanticIdentity.Candidate(CandidateSelector.declaration(selection).refined()),
        )
        .refined()
}

private fun traceMemberSelection(owner: SymbolSelector, name: String, offset: Int): SymbolDiscoverySelection {
    val path = Path.of(owner.file.stableValue)
    val candidate =
        SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.SYMBOL,
                name,
                owner.lease,
                path,
                path.toUri().toString(),
                offset,
            )
            .refined()
    return SymbolDiscoverySelection.restore(
            owner.lease,
            owner.scope,
            candidate,
            owner.constraints.copy(
                declarationKinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.FUNCTION)).refined()
            ),
        )
        .refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
