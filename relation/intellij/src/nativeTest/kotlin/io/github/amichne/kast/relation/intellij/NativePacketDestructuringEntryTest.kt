package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** These local mechanism cases supplement the original S2 query. They do not replace its producer or full route. */
class NativePacketDestructuringEntryTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-destructuring-entry-jdk"

    fun testOriginalInitializerCarriesItsTrackedFieldsToTheActualComponentReceiver() {
        val case = sourceCase()
        val source = initializer(case)
        val step = step(case.fixture, source)
        val receivers = step.transfers.filter { it.kind == ValueTransferKind.RECEIVER }.map { it.target }
        assertTrue(
            "C2.7 needs initializer818:917 to actual entry806:814 Receiver. " +
                "Frontier=${step.obligations.map { it.cause }}. Source component1 and bind remain NOT_REACHED",
            receiver(case) in receivers,
        )
    }

    fun testK2GroundedSyntheticReceiverSetupEntersTheExactComponentExtensionFormal() {
        val case = sourceCase()
        val source = receiver(case)
        println(
            "DESTRUCTURING_ENTRY_SETUP kind=SYNTHETIC_TYPED_RECEIVER " +
                "k2=EXACT_ORIGINAL_COMPONENT1 first_transfer=NOT_ASSERTED"
        )
        val step = step(case.fixture, source)
        val entries = step.transfers.filter { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        val position = ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)
        val matching = entries.filter { it.target.role == ValueRole.Formal(position) }
        assertEquals(
            "C2.7 needs source OutcomeScope.component1 EXTENSION entry from actual entry806:814. " +
                "Frontier=${step.obligations.map { it.cause }}. Bind and matched component return remain NOT_REACHED",
            1,
            matching.size,
        )
        val transfer = matching.single()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Enter
        val callee = ValueExecutionOwner.declaration(case.call.callable)
        val frame = ValueCallFrame.direct(case.call, callee).nativeRefined()
        assertEquals(frame, operation.frame)
        assertEquals(position, operation.position)
        assertEquals(callee, transfer.target.executionOwner)
        assertEquals(callee.range, transfer.target.range)
        assertEquals(source.valuePath, transfer.target.valuePath)
        assertEquals(source.predicates, transfer.target.predicates)
        assertEquals(source.callStack.frames + frame, transfer.target.callStack.frames)
        assertTrue(
            entries.none {
                it.target.role == ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH))
            }
        )
    }

    private fun sourceCase(): DestructuringEntryCase {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture)
        ReadAction.run<RuntimeException> { source.capture() }
        val expected = ReadAction.compute<DestructuringProbeExpectation, RuntimeException> { source.expectation() }
        val component = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "component1"))
        val call =
            ValueInvocation.fromCompiler(
                    enclosing = RelationEndpoint.subject(expected.enclosing),
                    range = ExactDeclarationTextRange.parse(ENTRY_START, ENTRY_END).nativeRefined(),
                    callable = RelationEndpoint.subject(component),
                    executionOwner = expected.executionOwner,
                )
                .nativeRefined()
        val fields = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val tracked =
            ValueAccessPath.fromSegments(
                    listOf(
                        ValuePathSegment.Field(fields.field(OUTCOME, "Success", "value")),
                        ValuePathSegment.Field(fields.field(CLIENT, "ClientResponse", "links")),
                    )
                )
                .nativeRefined()
        return DestructuringEntryCase(fixture = fixture, expected = expected, call = call, tracked = tracked)
    }

    private fun initializer(case: DestructuringEntryCase): ValueSite {
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(case.expected.enclosing),
                RelationMeaning.References,
                case.fixture.budget,
            )
        val found = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = case.fixture.lease,
                    enclosing = case.expected.enclosing,
                    anchor = case.expected.initializer,
                    domain = domain,
                    model = case.fixture.model,
                )
        }
        assertTrue("The independently proved original initializer did not admit", found is ValueSiteResolution.Found)
        val site = (found as ValueSiteResolution.Found).site
        assertEquals(case.expected.executionOwner, site.executionOwner)
        return site.withState(valuePath = case.tracked).nativeRefined()
    }

    private fun receiver(case: DestructuringEntryCase): ValueSite =
        ValueSite.fromCompiler(
                enclosing = RelationEndpoint.subject(case.expected.enclosing),
                range = case.call.range,
                role = ValueRole.Receiver(case.call, ValueReceiverKind.EXTENSION),
                executionOwner = case.expected.executionOwner,
                valuePath = case.tracked,
            )
            .nativeRefined()

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        when (read) {
            is ValueFlowRead.Observed ->
                println(
                    "DESTRUCTURING_ENTRY_READ variant=OBSERVED source=${source.range} " +
                        "pathDepth=${source.valuePath.depth} " +
                        "transfers=${read.step.transfers.map { it.kind to it.target.range }} " +
                        "causes=${read.step.obligations.map { it.cause }}"
                )
            is ValueFlowRead.Rejected -> println("DESTRUCTURING_ENTRY_READ variant=REJECTED cause=${read.cause}")
            is ValueFlowRead.ContractRejected ->
                println("DESTRUCTURING_ENTRY_READ variant=CONTRACT_REJECTED cause=${read.cause}")
            is ValueFlowRead.Suspended -> println("DESTRUCTURING_ENTRY_READ variant=SUSPENDED cause=${read.cause}")
        }
        assertTrue("The exact destructuring site did not produce a native read", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private companion object {
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val ENTRY_START = 806
        const val ENTRY_END = 814
    }
}

private data class DestructuringEntryCase(
    val fixture: NativePacketValueFixture,
    val expected: DestructuringProbeExpectation,
    val call: ValueInvocation,
    val tracked: ValueAccessPath,
)
