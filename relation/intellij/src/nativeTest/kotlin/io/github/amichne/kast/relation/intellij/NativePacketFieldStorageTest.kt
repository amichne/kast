package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** Supplemental field controls do not replace the original packet seeds. */
class NativePacketFieldStorageTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-field-storage-jdk"

    fun testStoredPropertyProjectsTheField() {
        val step = projection("fromStored")
        assertEquals(listOf(ValueTransferKind.FIELD_READ), step.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), step.obligations)
    }

    fun testAbstractPropertyHasNoProvenStorage() = rejected("fromAbstract")

    fun testMutablePropertyKeepsItsFrontier() = rejected("fromMutable")

    fun testComputedPropertyKeepsItsFrontier() = rejected("fromComputed")

    private fun rejected(function: String) {
        val step = projection(function)
        assertEquals("Unproven field storage escaped: $function", emptyList<Any>(), step.transfers)
        assertEquals(listOf(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY), step.obligations.map { it.cause })
    }

    private fun projection(function: String): ValueFlowStep {
        val fixture = packetFixture(listOf(CONTROLS))
        val property =
            fixture.argumentExpression(
                PacketDeclaration(CONTROLS, "FieldStorageControls", function),
                "StoredWireRequest",
                "request.destination",
            )
        val receiver =
            ExactDeclarationTextRange.parse(
                    property.anchor.startInclusive,
                    property.anchor.startInclusive + "request".length,
                )
                .nativeRefined()
        val resolved = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = property.enclosing,
                    anchor = receiver,
                    domain = RelationRequest.start(property.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue(resolved is ValueSiteResolution.Found)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request =
                        ValueFlowRequest(
                            (resolved as ValueSiteResolution.Found).site,
                            fixture.budget,
                            RelationSearchBoundary.RETAINED_SUBJECT,
                        ),
                    model = fixture.model,
                )
        }
        assertTrue(result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/FieldStorageControls.kt"
    }
}
