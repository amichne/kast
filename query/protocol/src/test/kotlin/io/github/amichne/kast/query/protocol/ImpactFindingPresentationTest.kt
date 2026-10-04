package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactFindingPresentationTest {
    @Test
    fun `admitted one finding grant bounds construction before projection and preserves original links`() = runTest {
        val fixture = ImpactFindingFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val budget =
            fixture.budget.copy(
                resources =
                    fixture.budget.resources.copy(
                        resultLimit = io.github.amichne.kast.kernel.ResultLimit.parse(1).value()
                    )
            )
        val first =
            protocol.execute(
                QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.FINDINGS),
                fixture.symbols.authority,
                budget,
            ) as OperationOutcome.Qualified
        assertTrue(QueryLimitationDocument.RESULT_LIMIT_REACHED in first.qualification.limitations)
        val original = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        val pages =
            (0..1).map { cursor ->
                protocol
                    .execute(
                        QueryRunRequest.ReadResult.impactWitness(
                            fixture.reference,
                            ImpactWitnessSectionDocument.FINDINGS,
                            QueryResultCursor.parse(cursor).value(),
                        ),
                        fixture.symbols.authority,
                        budget,
                    )
                    .payload()
            }
        assertEquals(listOf(1, 1), pages.map { it.items.values.size })
        assertEquals(original.items.values, pages.flatMap { it.items.values })
        assertEquals(1, pages.first().nextCursor!!.value)
        assertEquals(null, pages.last().nextCursor)
        for (page in pages) {
            assertEquals(Refinement.Refined(Unit), page.validateImpactAccounting())
            assertEquals(
                (original.impactAccounting as ImpactAccountingDocument.Investigated).status,
                (page.impactAccounting as ImpactAccountingDocument.Investigated).status,
            )
        }
    }

    @Test
    fun `compact retained findings link both original routes to full path rows without semantic replay`() = runTest {
        val fixture = ImpactFindingFixture()
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("Unexpected semantic replay")
                },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        val findings =
            page.items.values.map {
                ((it as QueryResultItemDocument.ImpactWitness).item.witness as ImpactWitnessDocument.Finding).finding
            }
        val paths = fullPaths(protocol, fixture)

        assertEquals(0, executions)
        assertEquals(listOf(0L, 1L), findings.map { it.path.pathOrdinal.value })
        assertEquals(paths.map { it.rowId }, findings.map { it.path.pathRowId })
        assertNotEquals(findings[0].path.pathRowId, findings[1].path.pathRowId)
        assertEquals(paths.map { it.path.producer }, findings.map { it.producer })
        assertEquals(findings[0].destination, findings[1].destination)
        assertNotEquals(paths[0].path.steps, paths[1].path.steps)
        val accounting = page.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(2L, accounting.originalPathCount.value)
        assertEquals(findings[1].path.pathRowId, fullPaths(protocol, fixture, 1).single().rowId)
        assertEquals(0, executions)
    }

    @Test
    fun `finding cursor drain preserves large view links closure and required compact encoded shape`() = runTest {
        val fixture = ImpactFindingFixture()
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("Unexpected semantic replay")
                },
                fixture.symbols.references,
                fixture.store,
            )
        val original = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        val prefix = original.presentationPrefix(1).value()
        val next =
            fixture
                .readWitness(
                    protocol,
                    ImpactWitnessSectionDocument.FINDINGS,
                    prefix.nextCursor!!.value,
                )
                .evidence
                .payload
        assertEquals(original.items.values, prefix.items.values + next.items.values)
        assertEquals(original.question, next.question)
        assertEquals(
            (original.impactAccounting as ImpactAccountingDocument.Investigated).status,
            (next.impactAccounting as ImpactAccountingDocument.Investigated).status,
        )
        assertInstanceOf(
            ImpactAccountingStatusDocument.SelectedSubset::class.java,
            (next.impactAccounting as ImpactAccountingDocument.Investigated).status,
        )
        assertEquals(Refinement.Refined(Unit), prefix.validateImpactAccounting())
        assertEquals(Refinement.Refined(Unit), next.validateImpactAccounting())
        val finding =
            ((next.items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                    as ImpactWitnessDocument.Finding)
                .finding
        assertCompactShapeAndSize(finding, fullPaths(protocol, fixture, 1).single())
        assertEquals(0, executions)
    }

    @Test
    fun `finding projection rejects absent or mismatched original row count before constructing documents`() {
        val fixture = ImpactFindingFixture()
        val restored =
            fixture.store.restoreResult(fixture.reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        val presentation =
            RetainedQueryPresentation.create(
                    restored,
                    QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.FINDINGS),
                    io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
                )
                .value()
        val projector = QueryItemProjector(fixture.symbols.references)
        val output = QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.FINDINGS)
        assertEquals(QueryProjection.Rejected, projector.projectItems(output, presentation.result.rows))
        assertEquals(
            QueryProjection.Rejected,
            projector.projectItems(output, presentation.result.rows, presentation.rowIds.take(1)),
        )
        assertEquals(
            2,
            (projector.projectItems(output, presentation.result.rows, presentation.rowIds) as QueryProjection.Projected)
                .values
                .size,
        )
    }

    private suspend fun fullPaths(
        protocol: CanonicalQueryProtocol,
        fixture: ImpactFindingFixture,
        cursor: Int = 0,
    ): List<QueryResultItemDocument.ValuePath> =
        protocol
            .execute(
                QueryRunRequest.ReadResult.valuePaths(fixture.reference, QueryResultCursor.parse(cursor).value()),
                fixture.symbols.authority,
                fixture.budget,
            )
            .payload()
            .items
            .values
            .map { it as QueryResultItemDocument.ValuePath }

    private fun assertCompactShapeAndSize(finding: ImpactFindingDocument, expanded: QueryResultItemDocument.ValuePath) {
        val encoded = Json.encodeToJsonElement(ImpactFindingDocument.serializer(), finding).jsonObject
        assertEquals(
            setOf("path", "producer", "destination", "representation", "terminal", "boundaryObligations"),
            encoded.keys,
        )
        assertEquals(setOf("pathOrdinal", "pathRowId"), encoded.getValue("path").jsonObject.keys)
        val fullBytes =
            Json.encodeToString(
                    io.github.amichne.kast.protocol.contract.ImpactPathDocument.serializer(),
                    expanded.path,
                )
                .toByteArray()
                .size
        val compactBytes = Json.encodeToString(ImpactFindingDocument.serializer(), finding).toByteArray().size
        assertTrue(compactBytes < fullBytes, "compact=$compactBytes full=$fullBytes")
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value

private fun io.github.amichne.kast.kernel.OperationOutcome<QueryRunResult, *, *>.payload(): QueryRunResult =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload
        is OperationOutcome.Qualified -> evidence.payload
        is OperationOutcome.Rejected -> error("Expected admitted retained response: $reason")
    }
