package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.presentationSuffix
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.protocol.contract.validateImpactCompletion
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactWitnessRecord
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ImpactWitnessPresentationTest {
    @Test
    fun `read result exposes original producer invocation and flattened native records without replay`() = runTest {
        val fixture = ImpactWitnessPresentationFixture()
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
        val output = fixture.readWitness(protocol, ImpactWitnessSectionDocument.NATIVE_READS, 1)
        assertEquals(0, executions)
        assertEquals(fixture.request.let(QueryQuestionDocument::from), output.evidence.payload.question)
        val items = output.evidence.payload.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item }
        assertEquals(listOf(1L, 2L), items.map { it.ordinal.value })
        assertInstanceOf(ImpactWitnessDocument.CompilerTransfer::class.java, items[0].witness)
        assertInstanceOf(ImpactWitnessDocument.NativeRead::class.java, items[1].witness)
        val accounting = output.evidence.payload.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(0L, accounting.pagePathCount.value)
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(2L, accounting.originalObservationCount.value)
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), accounting.status)
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(1),
                count(3),
                count(3),
            ),
            accounting.view,
        )
        assertEquals(Refinement.Refined(Unit), output.evidence.payload.validateImpactAccounting())
        assertNull(output.evidence.payload.nextCursor)
        val producerPage = fixture.readWitness(protocol, ImpactWitnessSectionDocument.PRODUCERS)
        val producer =
            ((producerPage.evidence.payload.items.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
                as ImpactWitnessDocument.Producer)
        assertEquals(fixture.producer.invocation.impactDocument().value(), producer.invocation)
        assertEquals(0, executions)
    }

    @Test
    fun `same retained result and section preserve stable ordinals empty sections and cursor bounds`() {
        val fixture = ImpactWitnessPresentationFixture()
        val restored =
            fixture.store.restoreResult(fixture.reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        val request =
            QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.NATIVE_READS)
        val first =
            RetainedQueryPresentation.create(
                    restored,
                    request,
                    io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
                )
                .value()
        val second =
            RetainedQueryPresentation.create(
                    restored,
                    request,
                    io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
                )
                .value()
        assertSame(fixture.ledger, (first.result.rows as QueryRows.ImpactWitness).view.ledger)
        assertEquals(
            (first.result.rows as QueryRows.ImpactWitness).values,
            (second.result.rows as QueryRows.ImpactWitness).values,
        )
        assertEquals(emptyList<QueryResultRowReference>(), first.rowIds)
        assertEquals(3, first.window.resultEnd.value)
        val empty =
            RetainedQueryPresentation.create(
                    restored,
                    QueryRunRequest.ReadResult.impactWitness(fixture.reference, ImpactWitnessSectionDocument.MODELS),
                    io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
                )
                .value()
        assertEquals(emptyList<QueryImpactWitnessRecord>(), (empty.result.rows as QueryRows.ImpactWitness).values)
        assertNotNull(empty.coverage)
        assertEquals(
            Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE),
            RetainedQueryPresentation.create(
                restored,
                QueryRunRequest.ReadResult.impactWitness(
                    fixture.reference,
                    ImpactWitnessSectionDocument.NATIVE_READS,
                    QueryResultCursor.parse(4).value(),
                ),
                io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
            ),
        )
    }

    @Test
    fun `connectivity only retained paths cannot manufacture original witness ledger`() {
        val fixture = ImpactWitnessPresentationFixture()
        val weak =
            QueryRetainedResult.capture(
                    fixture.symbols.authority,
                    QueryExecutionResult.Qualified(
                        QueryResult(QueryRows.ValuePaths.of(fixture.ledger.paths), emptyList()),
                        QueryCoverage.Qualified.create(
                                QueryCount.parse(1).value(),
                                setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
                            )
                            .value(),
                    ),
                )
                .value()
        val issued = fixture.store.issueResult(fixture.request, weak) as QueryResultIssuance.Issued
        val restored =
            fixture.store.restoreResult(issued.reference, fixture.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(
            Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            RetainedQueryPresentation.create(
                restored,
                QueryRunRequest.ReadResult.impactWitness(issued.reference, ImpactWitnessSectionDocument.PRODUCERS),
                io.github.amichne.kast.kernel.ResultLimit.parse(100).value(),
            ),
        )
    }

    @Test
    fun `path fitting preserves complete original closure through its retained presentation window`() = runTest {
        val fixture = ImpactWitnessPresentationFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val complete =
            protocol.execute(
                QueryRunRequest.ReadResult.valuePaths(fixture.reference),
                fixture.symbols.authority,
                fixture.budget,
            ) as OperationOutcome.Complete
        val original = complete.evidence.payload
        val prefix = original.presentationPrefix(0).value()
        val accounting = prefix.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(0L, accounting.pagePathCount.value)
        assertEquals(1L, accounting.originalPathCount.value)
        assertEquals(2L, accounting.originalObservationCount.value)
        assertEquals(ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged), accounting.status)
        assertEquals(QueryResultCursor.Start, prefix.nextCursor)
        assertEquals(Refinement.Refined(Unit), prefix.validateImpactAccounting())
        assertEquals(Refinement.Refined(Unit), prefix.validateImpactCompletion())
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED),
            prefix.copy(presentationWindow = null).validateImpactCompletion(),
        )
        val required = bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW))
        val unresolved =
            original.copy(
                impactAccounting =
                    (original.impactAccounting as ImpactAccountingDocument.Investigated).copy(
                        status = ImpactAccountingStatusDocument.Unresolved(required)
                    )
            )
        val sliced = unresolved.presentationSuffix(1).value().impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(
            ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Unresolved(required)),
            sliced.status,
        )
        assertEquals(0L, sliced.pagePathCount.value)
    }

    @Test
    fun `witness fitting preserves ledger counts and section ordinals across prefix and suffix`() = runTest {
        val fixture = ImpactWitnessPresentationFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.NATIVE_READS)
        val original = page.evidence.payload
        assertRejectedWitnessAccounting(original, fixture)
        val prefix = original.presentationPrefix(1).value()
        val suffix = original.presentationSuffix(1).value()
        val originalAccounting = original.impactAccounting as ImpactAccountingDocument.Investigated
        for (result in listOf(prefix, suffix)) {
            val accounting = result.impactAccounting as ImpactAccountingDocument.Investigated
            assertEquals(originalAccounting.originalPathCount, accounting.originalPathCount)
            assertEquals(originalAccounting.originalObservationCount, accounting.originalObservationCount)
            assertEquals(originalAccounting.seeds, accounting.seeds)
            assertEquals(originalAccounting.status, accounting.status)
            assertEquals(0L, accounting.pagePathCount.value)
            assertEquals(Refinement.Refined(Unit), result.validateImpactAccounting())
        }
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(0),
                count(1),
                count(3),
            ),
            (prefix.impactAccounting as ImpactAccountingDocument.Investigated).view,
        )
        assertEquals(QueryResultCursor.parse(1).value(), prefix.nextCursor)
        assertEquals(
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.NATIVE_READS,
                count(1),
                count(3),
                count(3),
            ),
            (suffix.impactAccounting as ImpactAccountingDocument.Investigated).view,
        )
        assertNull(suffix.nextCursor)
        assertEquals(
            listOf(1L, 2L),
            suffix.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.ordinal.value },
        )
    }

    @Test
    fun `resumed native witness exposes each actual grant separately from accumulated work`() = runTest {
        val fixture = ImpactWitnessPresentationFixture(resumedBinding = true)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val output = fixture.readWitness(protocol, ImpactWitnessSectionDocument.NATIVE_READS)
        val native =
            output.evidence.payload.items.values
                .map { (it as QueryResultItemDocument.ImpactWitness).item.witness }
                .filterIsInstance<ImpactWitnessDocument.NativeRead>()
                .last()
        assertEquals(7L, native.examinedWorkUnits.value)
        assertEquals(3L, native.domain.budget.maxWorkUnits.value)
        assertEquals(listOf(3L, 4L), native.receipts.values.map { it.domain.budget.maxWorkUnits.value })
        assertEquals(listOf(3L, 4L), native.receipts.values.map { it.examinedWorkUnits.value })
        val encoded = Json.encodeToJsonElement(ImpactWitnessDocument.serializer(), native).jsonObject
        assertEquals(
            setOf(
                "type",
                "observationOrdinal",
                "source",
                "domain",
                "examinedWorkUnits",
                "retainedBytes",
                "terminal",
                "transferCount",
                "obligationCount",
                "receipts",
            ),
            encoded.keys,
        )
        val receipts = encoded.getValue("receipts").jsonArray
        assertEquals(2, receipts.size)
        receipts.forEachIndexed { index, receipt ->
            val item = receipt.jsonObject
            assertEquals(setOf("domain", "examinedWorkUnits", "returnedResults", "returnedBytes"), item.keys)
            assertEquals((index + 3).toString(), item.getValue("examinedWorkUnits").jsonPrimitive.content)
            assertEquals("0", item.getValue("returnedResults").jsonPrimitive.content)
            val grant = item.getValue("domain").jsonObject.getValue("budget").jsonObject
            assertEquals((index + 3).toString(), grant.getValue("maxWorkUnits").jsonPrimitive.content)
            assertEquals("1", grant.getValue("maxResults").jsonPrimitive.content)
            assertEquals("100000", grant.getValue("maxReturnedBytes").jsonPrimitive.content)
        }
    }

    private fun assertRejectedWitnessAccounting(original: QueryRunResult, fixture: ImpactWitnessPresentationFixture) {
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING),
            original.copy(impactAccounting = ImpactAccountingDocument.NotApplicable).validateImpactAccounting(),
        )
        val wrongRecord =
            (original.items.values.first() as QueryResultItemDocument.ImpactWitness)
                .item
                .copy(witness = ImpactWitnessDocument.ProducerSiteOnly(fixture.producer.site.impactDocument().value()))
        val mixed =
            original.copy(
                items =
                    bounded(listOf(QueryResultItemDocument.ImpactWitness(wrongRecord)) + original.items.values.drop(1))
            )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            mixed.validateImpactAccounting(),
        )
    }

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).value()
}

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
