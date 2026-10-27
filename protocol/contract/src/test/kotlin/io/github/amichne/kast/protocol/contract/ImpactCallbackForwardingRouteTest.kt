package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackForwardingRouteTest {
    @Test
    fun `two forwarding frames survive encoding with distinct supplier and invoker`() {
        val f = ImpactCallbackForwardingFixture()
        for (transfer in listOf(f.entry(), f.returned())) {
            assertTrue(transfer.admitsEvidence())
            val encoded = Json.encodeToString(transfer)
            val capture =
                Json.parseToJsonElement(encoded)
                    .jsonObject
                    .getValue("evidence")
                    .jsonObject
                    .getValue("operation")
                    .jsonObject
                    .getValue("invocation")
                    .jsonObject
            assertEquals(2, capture.getValue("forwardings").jsonArray.size)
            val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(encoded)
            assertEquals(transfer, decoded)
            assertTrue(decoded.admitsEvidence())
            assertEquals(f.stack, decoded.target.callStack)
        }
        val route = requireNotNull(f.invocation.activeCallRoute(f.stack))
        assertEquals(f.supplierFrame, route.supplierFrame)
        assertEquals(f.invocationFrame, route.invocationFrame)
    }

    @Test
    fun `wire rejects missing reordered wrong root and noncontiguous active suffixes`() {
        val f = ImpactCallbackForwardingFixture()
        val wrongRoot = f.wrongSupplier()
        val stacks =
            listOf(
                emptyList(),
                listOf(f.intermediateFrame, f.invocationFrame),
                listOf(f.supplierFrame, f.invocationFrame, f.intermediateFrame),
                listOf(wrongRoot, f.intermediateFrame, f.invocationFrame),
                listOf(f.supplierFrame, wrongRoot, f.intermediateFrame, f.invocationFrame),
            )
        for (frames in stacks) assertRejectedAfterDecode(f.entry(frames = f.fixture.bounded(frames)))
    }

    @Test
    fun `wire rejects reordered route and a substituted formal position`() {
        val f = ImpactCallbackForwardingFixture()
        val captures =
            listOf(
                f.invocation.copy(forwardings = f.fixture.bounded(listOf(f.second, f.first))),
                f.invocation.copy(
                    forwardings =
                        f.fixture.bounded(listOf(f.first.copy(sourcePosition = f.fixture.offset(1)), f.second))
                ),
                f.invocation.copy(
                    forwardings = f.fixture.bounded(listOf(f.first, f.second.copy(sourceParameter = f.range(486, 490))))
                ),
            )
        for (capture in captures) assertRejectedAfterDecode(f.entry(capture = capture))
    }

    @Test
    fun `wire keeps argument supplier separate from terminal invocation frame`() {
        val f = ImpactCallbackForwardingFixture()
        val entry = f.entry()
        val operation =
            (entry.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.CallbackArgumentEntry
        assertRejectedAfterDecode(
            entry.copy(
                evidence = ImpactTransferEvidenceDocument.Source(operation.copy(supplierFrame = f.invocationFrame))
            )
        )
        val returned = f.returned()
        assertRejectedAfterDecode(
            returned.copy(
                evidence =
                    ImpactTransferEvidenceDocument.Source(
                        ImpactSourceOperationDocument.CallbackReturn(f.invocation, f.supplierFrame)
                    )
            )
        )
    }

    @Test
    fun `wire rejects capture start return when forwarded supplier frames are absent`() {
        val f = ImpactCallbackForwardingFixture()
        val returned = f.returned()
        assertRejectedAfterDecode(
            returned.copy(
                source = returned.source.copy(callStack = EmptyImpactCallStack),
                target = returned.target.copy(callStack = f.fixture.bounded(listOf(f.invocationFrame))),
            )
        )
    }

    private fun assertRejectedAfterDecode(transfer: ImpactCompilerTransferDocument) {
        val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(transfer))
        assertFalse(decoded.admitsEvidence())
    }
}
