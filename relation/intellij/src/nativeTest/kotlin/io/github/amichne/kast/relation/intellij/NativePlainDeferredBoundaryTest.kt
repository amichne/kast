package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** The plain slice keeps nested execution outside native admission and qualifies conditional receiver reads. */
class NativePlainDeferredBoundaryTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "plain-deferred-boundary-jdk"

    fun testLambdaValueSiteRetainsNestedExecutionRejection() = rejectsNested("lambdaRequest")

    fun testAnonymousFunctionValueSiteRetainsNestedExecutionRejection() = rejectsNested("anonymousRequest")

    /** Since cfdfcf4e45 the compiler's smart cast qualifies the field read instead of leaving an obligation. */
    fun testSubtypeReceiverFieldCarriesItsStableReceiverRefinement() = runBlocking {
        val fixture = packetFixture(listOf(CONTROL))
        val owner = fixture.callableDeclaration(PacketDeclaration(CONTROL, "", "destination"))
        val source =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(owner),
                    owner.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .nativeRefined()
        val read =
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        assertTrue("Conditional receiver read failed: $read", read is ValueFlowRead.Observed)
        val step = (read as ValueFlowRead.Observed).step
        assertEquals(
            "Stable receiver facts retained obligations",
            emptyList<ValueFlowUnsupportedCause>(),
            step.obligations.map { it.cause },
        )
        assertEquals(
            listOf(ValueTransferKind.LOCAL_READ, ValueTransferKind.FIELD_READ),
            step.transfers.map { it.kind },
        )
        val field = step.transfers.single { it.kind == ValueTransferKind.FIELD_READ }
        val condition = field.target.conditions.facts.single()
        assertTrue("Unexpected field condition: $condition", condition is ValueCondition.StableReceiver)
        condition as ValueCondition.StableReceiver
        assertTrue(condition.origin.matches(source))
        assertEquals(field.target.range, condition.useRange)
        assertEquals(ValueReceiverKind.EXTENSION, condition.kind)
        assertEquals("fixture.controls.deferred.Choice", condition.originalType.value)
        assertEquals("fixture.controls.deferred.Selected", condition.refinedType.value)
    }

    private fun rejectsNested(name: String) = runBlocking {
        val fixture = packetFixture(listOf(CONTROL))
        val selected =
            fixture.selection(
                PacketDeclaration(CONTROL, "", name),
                "Request",
                PacketDeclaration(CONTROL, "Request", "<init>"),
            )
        val read =
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = selected.enclosing,
                    anchor = selected.anchor,
                    domain = RelationRequest.start(selected.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        assertEquals(ValueSiteResolution.Rejected(ValueFlowRejection.NESTED_EXECUTION), read)
    }

    private companion object {
        const val CONTROL = "src/main/kotlin/fixture/controls/DeferredPlainControls.kt"
    }
}
