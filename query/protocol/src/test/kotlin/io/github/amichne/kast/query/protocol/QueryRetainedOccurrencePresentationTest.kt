package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOwnershipDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryRetainedOccurrencePresentationTest {
    private val fixture = RelationPagingFixture.published()
    private val store = QueryStateStore(clock = { 0L })
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(100).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )

    @Test
    fun `small occurrence pages preserve complete producer coverage without semantic replay`() = runTest {
        val issued = issueOccurrences((0 until 3).map(::confirmedOccurrence))
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Small occurrence pages must not execute semantic work") },
                fixture.references,
                store,
            )
        val smallBudget = budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(1).refined()))
        val pages =
            (0 until 3).map { cursor ->
                val pageRequest =
                    QueryRunRequest.ReadResult.occurrences(issued.reference, QueryResultCursor.parse(cursor).refined())
                val page = protocol.execute(pageRequest, fixture.authority, smallBudget) as OperationOutcome.Complete
                val replay = protocol.execute(pageRequest, fixture.authority, smallBudget) as OperationOutcome.Complete
                assertEquals(page.evidence.payload, replay.evidence.payload)
                assertEquals(1, page.evidence.payload.items.values.size)
                assertEquals(if (cursor < 2) cursor + 1 else null, page.evidence.payload.nextCursor?.value)
                page.evidence.payload
            }
        val items = pages.flatMap { it.items.values }.map { it as QueryResultItemDocument.ReferenceOccurrence }
        assertEquals(issued.rowIds, items.map { it.rowId })
        assertEquals(listOf(8, 10, 12), items.map { it.occurrence.occurrence.range.startInclusive.value })
        assertFileScopedEvidence(items)
        val empty =
            protocol.execute(
                QueryRunRequest.ReadResult.occurrences(issued.reference, QueryResultCursor.parse(3).refined()),
                fixture.authority,
                smallBudget,
            ) as OperationOutcome.Complete
        assertEquals(emptyList<QueryResultItemDocument>(), empty.evidence.payload.items.values)
        assertNull(empty.evidence.payload.nextCursor)
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertEquals(QueryCoverage.Complete(QueryCount.parse(3).refined()), restored.result.coverage)
        assertNull(restored.result.producerProgress)
    }

    @Test
    fun `reference admission bound contains every encoded row variant and repeated inline selectors`() = runTest {
        // Detached proofs are starting facts. The production codec establishes row bytes, not native performance.
        val admission = ReferenceAdmissionBoundFixture(fixture, budget, run())
        val texts = listOf("ordinary".repeat(128), "quoted\"backslash\\".repeat(32), "λ漢字𠀀".repeat(100))
        for (kind in CompilerSymbolKind.entries) {
            for (text in texts) {
                for (proof in admission.proofs(kind, text)) admission.assertFits(proof)
            }
        }
    }

    @Test
    fun `reference admission bound contains maximal signature arrays and both read authority variants`() = runTest {
        val admission = ReferenceAdmissionBoundFixture(fixture, budget, run())
        // Short type atoms maximize JSON list framing relative to canonical signature framing.
        for (authorityFixture in listOf(fixture, RelationPagingFixture.live())) {
            for (proof in
                admission.proofs(CompilerSymbolKind.FUNCTION, "T", 1_000, authorityFixture, exactScope = true)) {
                admission.assertFits(proof)
            }
        }
        for (proof in admission.proofs(CompilerSymbolKind.PROPERTY, "withoutReceiver", typeCount = 0)) {
            admission.assertFits(proof)
        }
    }

    @Test
    fun `retained file scoped occurrences page and replay without semantic execution or invented owners`() = runTest {
        // Compiler-confirmed facts are starting inputs. This case proves detached presentation, not compiler
        // resolution.
        val occurrences = (0 until 101).map(::confirmedOccurrence)
        val issued = issueOccurrences(occurrences)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Retained occurrence presentation must not execute semantic work") },
                fixture.references,
                store,
            )
        val request = QueryRunRequest.ReadResult.occurrences(issued.reference)
        val first = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        val replay = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryQuestionDocument.from(run()),
            first.evidence.payload.question,
        )
        assertEquals(first.evidence.payload.question, replay.evidence.payload.question)
        assertEquals(first.evidence.payload.items, replay.evidence.payload.items)
        assertEquals(100, first.evidence.payload.items.values.size)
        assertEquals(100, first.evidence.payload.nextCursor?.value)
        val last =
            protocol.execute(
                QueryRunRequest.ReadResult.occurrences(issued.reference, QueryResultCursor.parse(100).refined()),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(first.evidence.payload.question, last.evidence.payload.question)
        assertNull(last.evidence.payload.nextCursor)
        val items =
            (first.evidence.payload.items.values + last.evidence.payload.items.values).map {
                it as QueryResultItemDocument.ReferenceOccurrence
            }
        assertEquals(issued.rowIds, items.map { it.rowId })
        assertEquals(101, items.map { it.rowId }.toSet().size)
        assertEquals(
            (0 until 101).map { 8 + it * 2 },
            items.map { it.occurrence.occurrence.range.startInclusive.value },
        )
        assertFileScopedEvidence(items)
        assertSymbolPresentationRejected(protocol, issued)
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertEquals(
            occurrences,
            (restored.result as QueryRetainedResult.Occurrences).occurrences.map {
                (it as QueryOccurrence.Reference).value
            },
        )
        assertEquals(issued.rowIds, restored.rowIds)
    }

    private fun issueOccurrences(occurrences: List<RelationReferenceOccurrence>): QueryResultIssuance.Issued {
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Occurrences.of(occurrences.map(QueryOccurrence::Reference)), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(occurrences.size).refined()),
            )
        val retained = QueryRetainedResult.capture(fixture.authority, execution).refined()
        return store.issueResult(run(), retained) as QueryResultIssuance.Issued
    }

    private suspend fun assertSymbolPresentationRejected(
        protocol: CanonicalQueryProtocol,
        issued: QueryResultIssuance.Issued,
    ) {
        val wrong =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            wrong.reason,
        )
    }

    private fun assertFileScopedEvidence(items: List<QueryResultItemDocument.ReferenceOccurrence>) {
        items.forEachIndexed { index, item ->
            val context =
                if (index % 2 == 0) RelationReferenceContextDocument.IMPORT
                else RelationReferenceContextDocument.ALIASED_IMPORT
            assertEquals(context, item.occurrence.context)
            assertEquals(RelationReferenceOwnershipDocument.FileScoped(context), item.occurrence.ownership)
            assertEquals(RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED, item.occurrence.coverage)
        }
    }

    private fun confirmedOccurrence(index: Int): RelationReferenceOccurrence {
        val request =
            RelationRequest.start(
                fixture.selector,
                RelationMeaning.References,
                fixture.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val target =
            RelationConfirmedReferenceTarget.fromCompiler(
                    request.subject,
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                        (request.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
                    ),
                )
                .refined()
        val context = if (index % 2 == 0) RelationReferenceContext.IMPORT else RelationReferenceContext.ALIASED_IMPORT
        return RelationReferenceOccurrence.confirmed(
                request,
                target,
                RelationOccurrence.fromBoundary(request.subject.file, 8 + index * 2, 9 + index * 2).refined(),
                context,
                RelationReferenceOwnership.FileScoped(context),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .refined()
    }

    private fun run(output: QueryOutputDocument = QueryOutputDocument.Occurrences) =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
            ),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}

private class ReferenceAdmissionBoundFixture(
    private val fixture: RelationPagingFixture,
    private val budget: QueryBudget,
    private val request: QueryRunRequest.Run,
) {
    fun proofs(
        kind: CompilerSymbolKind,
        text: String,
        typeCount: Int = 3,
        authorityFixture: RelationPagingFixture = fixture,
        exactScope: Boolean = false,
    ): List<RelationReferenceOccurrence> {
        val selector = selector(kind, text, typeCount, authorityFixture, exactScope)
        val file = selector.file
        val owner =
            RelationEndpoint.resolve(
                    authorityFixture.authority,
                    selector.scope,
                    CompilerGroundedSymbolEvidence.fromSelector(selector),
                )
                .refined()
        val ownerships =
            listOf(
                RelationReferenceOwnership.DeclarationOwned(owner),
                RelationReferenceOwnership.FileScoped(RelationReferenceContext.IMPORT),
                RelationReferenceOwnership.FileScoped(RelationReferenceContext.ALIASED_IMPORT),
                RelationReferenceOwnership.FileScoped(RelationReferenceContext.FILE_ANNOTATION),
            ) + RelationOwnershipUnavailableCause.entries.map(RelationReferenceOwnership::Unavailable)
        return ownerships.mapIndexed { index, ownership ->
            val meaning = if (index % 2 == 0) RelationMeaning.References else RelationMeaning.TypeUses
            val request = RelationRequest.start(selector, meaning, fixture.budget)
            val confirmed =
                RelationConfirmedReferenceTarget.fromCompiler(
                        request.subject,
                        io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                            (request.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject)
                                .selector
                        ),
                    )
                    .refined()
            val context =
                when (ownership) {
                    is RelationReferenceOwnership.FileScoped -> ownership.context
                    is RelationReferenceOwnership.DeclarationOwned -> RelationReferenceContext.TYPE
                    is RelationReferenceOwnership.Unavailable -> RelationReferenceContext.CODE
                }
            RelationReferenceOccurrence.confirmed(
                    request,
                    confirmed,
                    RelationOccurrence.fromBoundary(file, Int.MAX_VALUE - 1, Int.MAX_VALUE).refined(),
                    context,
                    ownership,
                    RelationProvenance.entries[index % RelationProvenance.entries.size],
                )
                .refined()
        }
    }

    private fun selector(
        kind: CompilerSymbolKind,
        text: String,
        typeCount: Int,
        authorityFixture: RelationPagingFixture,
        exactScope: Boolean,
    ): SymbolSelector {
        val authority = authorityFixture.authority
        val controls = ((1..31) + (127..159)).joinToString("") { it.toChar().toString() }
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        authority.workspaceRoot,
                        Path.of(authority.workspaceRoot.value).resolve("$text$controls.kt"),
                    )
                    .refined()
            )
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    Int.MAX_VALUE,
                    text.take(512),
                    "sample.$text".take(1_024),
                    kind,
                    signature(kind, "sample.$text".take(1_024), typeCount),
                )
                .refined()
        val scope =
            if (exactScope)
                SymbolSearchScope.ExactFile(
                    file.path,
                    authorityFixture.selector.scope.sourceKinds,
                    authorityFixture.selector.scope.generatedSources,
                )
            else authorityFixture.selector.scope
        return SymbolSelector.issue(authority, scope, evidence)
    }

    private fun signature(kind: CompilerSymbolKind, identity: String, typeCount: Int): CanonicalCompilerSignature =
        when (kind) {
            CompilerSymbolKind.FUNCTION,
            CompilerSymbolKind.CONSTRUCTOR ->
                CanonicalCompilerSignature.function(
                    identity,
                    identity.takeIf { typeCount > 0 },
                    List(typeCount) { "T" },
                    List(typeCount) { "T" },
                    Int.MAX_VALUE,
                )
            CompilerSymbolKind.PROPERTY ->
                CanonicalCompilerSignature.property(
                    identity,
                    identity.takeIf { typeCount > 0 },
                    List(typeCount) { "T" },
                    identity,
                )
            CompilerSymbolKind.CLASSLIKE -> CanonicalCompilerSignature.classLike(identity)
            CompilerSymbolKind.TYPE_ALIAS -> CanonicalCompilerSignature.typeAlias(identity)
        }.refined()

    suspend fun assertFits(proof: RelationReferenceOccurrence) {
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Occurrences.of(listOf(QueryOccurrence.Reference(proof))), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            )
        val retainedStore = QueryStateStore(clock = { 0L })
        val retained = QueryRetainedResult.capture(proof.target.lease, execution).refined()
        val issued = retainedStore.issueResult(request, retained) as QueryResultIssuance.Issued
        val page =
            CanonicalQueryProtocol(
                    QueryOperations { error("Retained byte proof must not execute semantic work") },
                    CanonicalQueryReferences(),
                    retainedStore,
                )
                .execute(QueryRunRequest.ReadResult.occurrences(issued.reference), proof.target.lease, budget)
                as OperationOutcome.Complete
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(page) as WireEncoding.Encoded
        val row =
            Json.parseToJsonElement(encoded.document)
                .jsonObject
                .getValue("body")
                .jsonObject
                .getValue("result")
                .jsonObject
                .getValue("items")
                .jsonArray
                .single()
        assertEquals(1, page.evidence.payload.items.values.size)
        assertTrue(row.jsonObject.containsKey("row_id"), "The serialized proof must include its retained row identity")
        val rowBytes = row.toString().toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(
            rowBytes <= proof.projectedUtf8Size(),
            "Encoded ${proof.target.kind} ${ownershipName(proof)} row ($rowBytes bytes) " +
                "exceeded ${proof.projectedUtf8Size()}",
        )
    }

    private fun ownershipName(proof: RelationReferenceOccurrence): String =
        when (proof.ownership) {
            is RelationReferenceOwnership.DeclarationOwned -> "declaration-owned"
            is RelationReferenceOwnership.FileScoped -> "file-scoped"
            is RelationReferenceOwnership.Unavailable -> "unavailable"
        }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Byte proof fixture rejection: $failure")
        }
}
