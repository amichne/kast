package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryInvestigationCompletionFailureDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue

/** Scripted effect observations and independent assertions; the existing Case owns service execution. */
internal class RejectedPrefixRetentionCase(
    private val invocation: ValueInvocation,
    private val selector: SymbolSelector,
    private val branching: Boolean = false,
) {
    private val producer = invocation.resultSite()
    private val binding = site(2, ValueRole.LocalBinding)
    private val localRead = site(4, ValueRole.LocalRead)
    private val siblingRead = site(5, ValueRole.LocalRead)
    private val expectedSources =
        listOf(producer, binding, localRead) + if (branching) listOf(siblingRead) else emptyList()
    private val pathCount = if (branching) 2L else 1L
    private val observedSources = mutableListOf<ValueSite>()
    private val transfers =
        listOf(
            ValueTransfer.fromCompiler(producer, binding, ValueTransferKind.LOCAL_BINDING).value(),
            ValueTransfer.fromCompiler(binding, localRead, ValueTransferKind.LOCAL_READ).value(),
        ) +
            if (branching)
                listOf(ValueTransfer.fromCompiler(binding, siblingRead, ValueTransferKind.LOCAL_READ).value())
            else emptyList()
    private var seeds = 0
    private var unexpected = 0

    val flow = ValueFlowCompilerPort { input -> read(input) }
    val producerSeeds =
        object : ValueProducerSeedCompilerPort {
            override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                seeds++
                assertEquals(1, seeds)
                assertEquals(invocation.range, request.anchor)
                return ValueProducerSeedRead.Seeded(
                    ValueProducerSeed.fromCompiler(request, producer, invocation).value(),
                    RelationWorkCount.parse(1).value(),
                )
            }

            override suspend fun revalidate(
                selector: SymbolSelector,
                budget: RelationBudget,
            ): ValueModelDeclarationRead {
                unexpected++
                error("Unexpected model revalidation")
            }

            override suspend fun revalidateSite(request: ValueSiteRevalidationRequest): ValueModelSiteRead {
                unexpected++
                error("Unexpected model-site revalidation")
            }
        }

    private fun site(start: Int, role: ValueRole): ValueSite =
        ValueSite.fromCompiler(producer.enclosing, ExactDeclarationTextRange.parse(start, start + 1).value(), role)
            .value()

    private fun read(input: ValueFlowRequest): ValueFlowRead {
        val index = observedSources.size
        observedSources += input.source
        if (index >= expectedSources.size) {
            unexpected++
            error("Excess native observation after the rejected read")
        }
        assertEquals(expectedSources[index], input.source)
        if (index >= 2) {
            val cause =
                if (index == 2)
                    ValueFlowRejection.Native.Unexpected(
                        io.github.amichne.kast.relation.contract.ValueNativeOperation.FLOW_READ,
                        io.github.amichne.kast.relation.contract.ValueNativeUnexpectedFailure.fromBoundary(
                            kind = io.github.amichne.kast.relation.contract.ValueNativeUnexpectedKind.RUNTIME,
                            exceptionType = "java.lang.IllegalStateException",
                            adapterFrames = listOf("io.github.amichne.kast.Adapter.read:12"),
                            limits = io.github.amichne.kast.kernel.ReadLimits.Default,
                        ),
                    )
                else
                    ValueFlowRejection.Native.RelationRejected(
                        io.github.amichne.kast.relation.contract.ValueNativeRelationOrigin.IMPLEMENTATION_DISPATCH,
                        io.github.amichne.kast.relation.contract.RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION,
                    )
            return ValueFlowRead.Rejected(cause, RelationWorkCount.parse(3).value())
        }
        return ValueFlowRead.Observed(
            ValueFlowStep.fromCompiler(
                    source = input.source,
                    transfers = if (index == 0) transfers.take(1) else transfers.drop(1),
                    obligations = emptyList(),
                    terminal = ValueFlowTerminal.SupportedDomainExhausted,
                    domain =
                        RelationRequest.start(
                            selector = selector,
                            meaning = RelationMeaning.References,
                            budget = input.budget,
                            boundary = input.boundary,
                        ),
                    examinedWorkUnits = RelationWorkCount.parse(1).value(),
                )
                .value()
        )
    }

    fun rejectedReference(original: QueryPublishedPage): QueryResultReference {
        val rejected = assertInstanceOf(OperationOutcome.Rejected::class.java, original)
        val failure = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, rejected.reason)
        val cause = assertInstanceOf(QueryCompletionCauseDocument.InvestigationUnproven::class.java, failure.cause)
        val unresolved =
            assertInstanceOf(
                QueryInvestigationCompletionFailureDocument.ObligationsUnresolved::class.java,
                cause.investigationFailure,
            )
        assertEquals(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW), unresolved.required.values)
        assertConsumed()
        return assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, failure.evidence).result
    }

    fun assertQualified(page: QueryPublishedPage, question: QueryQuestionDocument): QueryRunResult {
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, page)
        val body = qualified.evidence.payload as QueryRunResult
        assertEquals(question, body.question)
        assertInstanceOf(QueryResultInterpretationDocument.EvidenceOnly::class.java, body.interpretation)
        val accounting = assertInstanceOf(ImpactAccountingDocument.Investigated::class.java, body.impactAccounting)
        assertEquals(pathCount, accounting.originalPathCount.value)
        assertEquals(2L, accounting.originalObservationCount.value)
        assertEquals(pathCount, accounting.originalReadRejectionCount.value)
        assertEquals(ImpactRequestedBoundaryDocument.RetainedSeed, accounting.requestedDomain)
        val required =
            when (val status = accounting.status) {
                is ImpactAccountingStatusDocument.Unresolved -> status.required
                is ImpactAccountingStatusDocument.SelectedSubset ->
                    assertInstanceOf(ImpactClosureDocument.Unresolved::class.java, status.originalClosure).required
                ImpactAccountingStatusDocument.Conserved -> error("A retained slice discharged the original gap")
            }
        assertEquals(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW), required.values)
        assertConsumed()
        return body
    }

    suspend fun assertRetained(
        reference: QueryResultReference,
        read: suspend (QueryRunRequest.ReadResult, Int) -> QueryRunResult,
    ) {
        val first = read(QueryRunRequest.ReadResult.valuePaths(reference), 100)
        val items = first.items.values.map { assertInstanceOf(QueryResultItemDocument.ValuePath::class.java, it) }
        assertEquals(pathCount.toInt(), items.size)
        items.forEach { assertPath(it.path) }
        for (section in ImpactWitnessSectionDocument.entries) {
            val full = read(QueryRunRequest.ReadResult.impactWitness(reference, section), 100)
            assertSection(section, full, items)
            assertEquals(full.items.values, readWitnessPages(reference, section, read))
        }
        val emptySuffix =
            read(
                QueryRunRequest.ReadResult.valuePaths(reference, QueryResultCursor.parse(pathCount.toInt()).value()),
                1,
            )
        assertEquals(emptyList<QueryResultItemDocument>(), emptySuffix.items.values)
        val repeated = read(QueryRunRequest.ReadResult.valuePaths(reference), pathCount.toInt())
        assertEquals(first.items.values, repeated.items.values)
        assertEquals(first.impactAccounting, repeated.impactAccounting)
        assertConsumed()
    }

    private suspend fun readWitnessPages(
        reference: QueryResultReference,
        section: ImpactWitnessSectionDocument,
        read: suspend (QueryRunRequest.ReadResult, Int) -> QueryRunResult,
    ): List<QueryResultItemDocument> {
        val items = mutableListOf<QueryResultItemDocument>()
        var cursor = QueryResultCursor.Start
        while (true) {
            val page = read(QueryRunRequest.ReadResult.impactWitness(reference, section, cursor), 1)
            items += page.items.values
            val next = page.nextCursor ?: break
            assertTrue(next.value > cursor.value)
            cursor = next
        }
        return items
    }

    private fun assertPath(path: ImpactPathDocument) {
        val steps = path.steps.values.map { assertInstanceOf(ImpactPathStepDocument.Compiler::class.java, it).transfer }
        assertEquals(
            listOf(ImpactTransferKindDocument.LOCAL_BINDING, ImpactTransferKindDocument.LOCAL_READ),
            steps.map { it.kind },
        )
        assertEquals(listOf(0, 2), steps.map { it.source.range.start.value })
        assertEquals(listOf(1, 3), steps.map { it.source.range.end.value })
        val lastStart = steps.last().target.range.start.value
        assertTrue(lastStart == 4 || (branching && lastStart == 5))
        assertEquals(listOf(2, lastStart), steps.map { it.target.range.start.value })
        assertEquals(listOf(3, lastStart + 1), steps.map { it.target.range.end.value })
        assertEquals(path.producer, steps.first().source)
        assertEquals(steps.first().target, steps.last().source)
        val terminal = assertInstanceOf(ImpactPathTerminalDocument.UnresolvedRead::class.java, path.terminal)
        val rejection = assertInstanceOf(ImpactReadRejectionDocument.Native::class.java, terminal.rejection)
        assertEquals(steps.last().target, rejection.source)
        assertEquals(ImpactRequestedBoundaryDocument.RetainedSeed, rejection.domain)
        assertEquals(
            expectedFailure(lastStart),
            rejection.cause,
        )
        assertEquals(3L, rejection.examinedWorkUnits.value)
    }

    private fun assertSection(
        section: ImpactWitnessSectionDocument,
        full: QueryRunResult,
        originals: List<QueryResultItemDocument.ValuePath>,
    ) {
        val witnesses = full.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.witness }
        when (section) {
            ImpactWitnessSectionDocument.MODELS -> assertEquals(emptyList<ImpactWitnessDocument>(), witnesses)
            ImpactWitnessSectionDocument.PRODUCERS -> {
                val producer = assertInstanceOf(ImpactWitnessDocument.Producer::class.java, witnesses.single())
                assertEquals(originals.first().path.producer, producer.site)
            }
            ImpactWitnessSectionDocument.READ_REJECTIONS -> {
                val rejections = witnesses.map { assertInstanceOf(ImpactWitnessDocument.ReadRejection::class.java, it) }
                assertEquals(
                    originals.map { (it.path.terminal as ImpactPathTerminalDocument.UnresolvedRead).rejection }.toSet(),
                    rejections.map { it.rejection }.toSet(),
                )
                rejections.forEach { witness ->
                    val rejection = witness.rejection as ImpactReadRejectionDocument.Native
                    assertEquals(expectedFailure(rejection.source.range.start.value), rejection.cause)
                    assertEquals(3L, witness.receipts.values.single().examinedWorkUnits.value)
                }
            }
            ImpactWitnessSectionDocument.NATIVE_READS -> {
                assertEquals(2, witnesses.filterIsInstance<ImpactWitnessDocument.NativeRead>().size)
                val expected =
                    originals
                        .flatMap { it.path.steps.values }
                        .map { (it as ImpactPathStepDocument.Compiler).transfer }
                        .toSet()
                assertEquals(
                    expected,
                    witnesses.filterIsInstance<ImpactWitnessDocument.CompilerTransfer>().map { it.transfer }.toSet(),
                )
            }
            ImpactWitnessSectionDocument.FINDINGS -> assertFindings(witnesses, originals)
            ImpactWitnessSectionDocument.SITE_ACCOUNTING -> Unit
        }
    }

    private fun assertFindings(
        witnesses: List<ImpactWitnessDocument>,
        originals: List<QueryResultItemDocument.ValuePath>,
    ) {
        val findings = witnesses.map { assertInstanceOf(ImpactWitnessDocument.Finding::class.java, it).finding }
        assertEquals(originals.map { it.rowId }.toSet(), findings.map { it.path.pathRowId }.toSet())
        findings.forEach { finding ->
            val original = originals.single { it.rowId == finding.path.pathRowId }
            val terminal = original.path.terminal as ImpactPathTerminalDocument.UnresolvedRead
            val compact =
                finding.terminal
                    as io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument.UnresolvedRead
            assertEquals(terminal.rejection, compact.rejection)
        }
    }

    private fun expectedFailure(start: Int): ImpactNativeReadRejectionDocument =
        if (start == 4)
            ImpactNativeReadRejectionDocument.Unexpected(
                operation = io.github.amichne.kast.protocol.contract.ImpactNativeOperationDocument.FLOW_READ,
                kind = io.github.amichne.kast.protocol.contract.ImpactNativeUnexpectedKindDocument.RUNTIME,
                exceptionType = "java.lang.IllegalStateException",
                adapterFrames = listOf("io.github.amichne.kast.Adapter.read:12"),
            )
        else
            ImpactNativeReadRejectionDocument.RelationRejected(
                io.github.amichne.kast.protocol.contract.ImpactNativeRelationOriginDocument.IMPLEMENTATION_DISPATCH,
                io.github.amichne.kast.protocol.contract.ImpactNativeRelationRejectionDocument
                    .COMPILER_CONTRACT_VIOLATION,
            )

    private fun assertConsumed() {
        assertEquals(1, seeds)
        assertEquals(expectedSources, observedSources)
        assertEquals(0, unexpected)
    }
}

private fun <T> Refinement<T, *>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
