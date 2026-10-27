package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackObservationIdentityTest {
    @Test
    fun `implementation observations retain callback selection formal and invocation identity`() {
        for (forwarded in listOf(false, true)) {
            val f = ImpactCallbackObservationIdentityFixture(forwarded)
            val capture = f.capture()
            val fresh = f.capture(f.observedTarget())
            assertNotEquals(capture, fresh)
            assertTrue(capture.hasSameCaptureIdentity(fresh))
            assertTrue(f.callback().selects(fresh))
            assertTrue(f.callback().hasSameExecutionIdentity(f.callback(fresh)))
            assertTrue(f.formal().hasSameValueIdentity(f.formal(fresh)))
            val call = ImpactInvocationReferenceDocument(f.range(38, 42), f.implementation.subject, f.callback())
            val observed = call.copy(executionOwner = f.callback(fresh))
            assertNotEquals(call, observed)
            assertTrue(call.hasSameInvocationIdentity(observed))
            assertTrue(
                ImpactCallFrameDocument(f.host, call, ImpactExecutionReferenceDocument(f.implementation.subject))
                    .hasSameFrameIdentity(
                        ImpactCallFrameDocument(
                            f.host,
                            observed,
                            ImpactExecutionReferenceDocument(f.implementation.subject),
                        )
                    )
            )
        }
    }

    @Test
    fun `entry and return accept equivalent observations and encode every observed counter`() {
        for (forwarded in listOf(false, true)) {
            val f = ImpactCallbackObservationIdentityFixture(forwarded)
            val fresh = f.capture(f.observedTarget())
            for (transfer in listOf(f.entry(fresh), f.returned(fresh))) {
                val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(transfer))
                assertEquals(transfer, decoded)
                assertTrue(decoded.admitsEvidence())
                val operation = (decoded.evidence as ImpactTransferEvidenceDocument.Source).operation
                val capture =
                    when (operation) {
                        is ImpactSourceOperationDocument.CallbackArgumentEntry -> operation.invocation
                        is ImpactSourceOperationDocument.CallbackReturn -> operation.invocation
                        else -> error("Expected callback operation")
                    }
                val selected =
                    if (forwarded) capture.forwardings.values.single().targetFrame.target else capture.supplyTarget
                val dispatch = (selected as ImpactCallTargetDocument.Implementation).dispatch
                assertEquals(7L, dispatch.examinedWorkUnits.value)
                assertEquals(400L, dispatch.encodedBytes.value)
            }
        }
    }

    @Test
    fun `callback identity rejects changed coverage ownership and selected implementation`() {
        for (forwarded in listOf(false, true)) {
            val f = ImpactCallbackObservationIdentityFixture(forwarded)
            val native = f.implementation
            val changedCoverage =
                native.target.copy(
                    dispatch =
                        native.dispatch.copy(
                            coverage =
                                ImpactImplementationCoverageDocument.TerminalIncomplete(
                                    f.values.count(2),
                                    f.values.bounded(listOf(RelationLimitationDocument.UNRESOLVED_TARGET)),
                                )
                        )
                )
            val changedOwnership =
                native.target.copy(
                    ownership =
                        native.target.ownership.copy(
                            root = native.target.ownership.root.copy(module = f.values.text("other"))
                        )
                )
            val sibling = native.dispatch.facts.values.last()
            val changedImplementation =
                native.target.copy(
                    relation = sibling,
                    ownership = native.target.ownership.copy(declaration = sibling.source.declaration),
                )
            for (target in listOf(changedCoverage, changedOwnership, changedImplementation)) {
                val changed = f.capture(target)
                assertFalse(f.capture().hasSameCaptureIdentity(changed))
                assertFalse(f.callback().selects(changed))
                assertFalse(f.formal().hasSameValueIdentity(f.formal(changed)))
                assertFalse(f.entry(changed).admitsEvidence())
                assertFalse(f.returned(changed).admitsEvidence())
            }
        }
    }

    @Test
    fun `callback identity retains ordered forwarding route parameter and argument evidence`() {
        val f = ImpactCallbackForwardingFixture()
        val variants =
            listOf(
                f.invocation.copy(forwardings = f.fixture.bounded(listOf(f.second, f.first))),
                f.invocation.copy(forwardings = f.fixture.bounded(listOf(f.first))),
                f.invocation.copy(
                    forwardings = f.fixture.bounded(listOf(f.first.copy(position = f.fixture.offset(1)), f.second))
                ),
                f.invocation.copy(
                    forwardings = f.fixture.bounded(listOf(f.first.copy(argument = f.range(311, 316)), f.second))
                ),
                f.invocation.copy(invocation = f.range(801, 831)),
            )
        for (changed in variants) assertFalse(f.invocation.hasSameCaptureIdentity(changed))
    }

    @Test
    fun `equivalent observations cannot make duplicate capture selection unique`() {
        val f = ImpactCallbackObservationIdentityFixture(false)
        val fresh = f.capture(f.observedTarget())
        val duplicated = f.callback().copy(invocations = f.values.bounded(listOf(f.capture(), fresh)))
        assertFalse(duplicated.admitsSelection())
        assertFalse(duplicated.selects(fresh))
    }
}
