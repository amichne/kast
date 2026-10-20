package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import kotlinx.coroutines.runBlocking

/** Original EXCLUDE admission uses the actual flow adapter after independent K2 prerequisites. */
class NativePacketReviewedReceiverAdmissionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-reviewed-receiver-admission-jdk"

    fun testOriginalExcludedFirstOrNullAdmitsOnlyItsExactReviewedReceiver() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val expected = expectation(fixture, ReceiverAdmissionCase.REVIEWED)
        val source = resolve(fixture, expected)
        val step = read(fixture, source)
        val matches = step.transfers.filter { it.kind == ValueTransferKind.RECEIVER }
        assertEquals(
            "Original EXCLUDE Receiver is absent. Causes=${step.obligations.map { it.cause }}. " +
                "Model application and full S2 remain NOT_RUN",
            1,
            matches.size,
        )
        val transfer = matches.single()
        assertEquals(source, transfer.source)
        assertEquals(expected.receiver, transfer.target.range)
        assertEquals(source.enclosing, transfer.target.enclosing)
        assertEquals(expected.owner, transfer.target.executionOwner)
        assertEquals(source.valuePath, transfer.target.valuePath)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(source.predicates, transfer.target.predicates)
        assertTrue(transfer.target.role is ValueRole.Receiver)
        val role = transfer.target.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(expected.call, role.call.range)
        assertEquals(source.enclosing, role.call.enclosing)
        assertEquals(expected.owner, role.call.executionOwner)
        assertEquals(fixture.lease.identity, role.call.basis)
        assertEquals(expected.identity, role.call.callable.compilerIdentity)
        assertEquals(expected.signature, role.call.signature)
        assertEquals(expected.file, role.call.callable.file)
    }

    fun testExcludedUnreviewedFirstKeepsTheActualExternalCallRefusal() {
        val fixture = packetFixture(listOf(V6))
        val expected = expectation(fixture, ReceiverAdmissionCase.UNREVIEWED)
        val source = resolve(fixture, expected)
        val step = read(fixture, source)
        assertTrue(
            "Unreviewed library call lost EXTERNAL_CALL",
            step.obligations.any { obligation ->
                obligation.cause == ValueFlowUnsupportedCause.EXTERNAL_CALL
            },
        )
        assertTrue(step.transfers.none { it.kind == ValueTransferKind.RECEIVER })
        assertTrue(step.applications.isEmpty())
    }

    private fun resolve(
        fixture: NativePacketValueFixture,
        expected: ReceiverAdmissionExpectation,
    ): ValueSite {
        val domain =
            RelationRequest.start(
                subject = RelationEndpoint.subject(expected.host),
                meaning = RelationMeaning.References,
                budget = fixture.budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
        assertEquals(
            SymbolLibraryPolicy.EXCLUDE,
            (domain.searchScope as SymbolSearchScope.Workspace).libraries,
        )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expected.host,
                    anchor = expected.receiver,
                    domain = domain,
                    model = fixture.model,
                )
        }
        val status =
            when (result) {
                is ValueSiteResolution.Found -> "FOUND"
                is ValueSiteResolution.Rejected -> "REJECTED cause=${result.cause}"
            }
        assertTrue(
            "Original expression admission failed: $status",
            result is ValueSiteResolution.Found,
        )
        val source = (result as ValueSiteResolution.Found).site
        assertEquals(ValueRole.ExpressionResult, source.role)
        assertEquals(expected.receiver, source.range)
        assertEquals(expected.owner, source.executionOwner)
        return source
    }

    private fun read(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request =
                        ValueFlowRequest(
                            source = source,
                            budget = fixture.budget,
                            boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                        ),
                    model = fixture.model,
                )
        }
        val status =
            when (result) {
                is ValueFlowRead.Observed -> "OBSERVED causes=${result.step.obligations.map { it.cause }}"
                is ValueFlowRead.Suspended -> "SUSPENDED cause=${result.cause}"
                is ValueFlowRead.Rejected -> "REJECTED cause=${result.cause}"
                is ValueFlowRead.ContractRejected -> "CONTRACT_REJECTED cause=${result.cause}"
            }
        assertTrue("Actual EXCLUDE flow read failed: $status", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun expectation(fixture: NativePacketValueFixture, case: ReceiverAdmissionCase) =
        NativePacketReviewedReceiverSource(project, getOrCreateProjectBaseDir(), fixture).expectation(case)

    private companion object {
        const val V6 = "src/main/kotlin/fixture/variants/v6/V6.kt"
    }
}
