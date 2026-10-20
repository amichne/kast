package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackDecodeDepthTest {
    @Test
    fun `a small direct frame admits the omitted default target`() {
        val frame = CallbackIdentityFixture().target.supplyingFrame.asFrame()
        val input = DefaultDirectFrameInput(frame.enclosing, frame.invocation, frame.callee)
        val encoded = Json.encodeToString(DefaultDirectFrameInput.serializer(), input)
        val decoded = Json.decodeFromString(ImpactCallFrameDocument.serializer(), encoded)
        assertEquals(ImpactCallTargetDocument.Direct, decoded.target)
        assertEquals(frame.invocation, decoded.invocation)
        assertEquals(frame.callee, decoded.callee)
    }

    @Test
    fun `omitted direct targets cannot hide an overcount callback proof`() {
        val original = encode(repeatedFrames(30))
        // This deliberately over-limit negative input omits only admitted DIRECT defaults.
        val encoded = original.replace(",\"target\":{\"type\":\"DIRECT\"}", "")
        assertTrue(encoded.length < original.length)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactCallFrameDocument.serializer(), encoded)
        }
    }

    @Test
    fun `an omitted owner execution remains a required-field decode failure`() {
        val f = CallbackIdentityFixture()
        val owner =
            QueryCallbackBodyBindingWireDocument(
                f.body,
                QueryCallbackBodySupplyWireDocument.Stored,
                QueryCallbackBindingWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
            )
        val original = Json.encodeToString(QueryCallbackBodyBindingWireDocument.serializer(), owner)
        val encoded = original.replace(",\"execution\":{\"type\":\"UNPROVEN\"}", "")
        assertTrue(encoded.length < original.length)
        val failure =
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(QueryCallbackBodyBindingWireDocument.serializer(), encoded)
            }
        assertTrue(failure.message.orEmpty().contains("execution"))
    }

    @Test
    fun `small callback frame trees decode with full typed invocation evidence`() {
        val frame = nestedFrames(2)
        val decoded = Json.decodeFromString(ImpactCallFrameDocument.serializer(), encode(frame))
        assertTrue(decoded.invocation is ImpactCallOccurrenceDocument.Function)
        assertTrue(decoded.target is ImpactCallTargetDocument.Callback)
        assertEquals(frame.callee, decoded.callee)
    }

    @Test
    fun `bounded input cannot bypass the callback depth limit through callable transfer frames`() {
        val encoded = encode(nestedFrames(QueryCallbackOwnerExecutionLimits.EXCLUSIVE_DEPTH))
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 1_000_000, "Bounded encoded input bytes=$bytes")
        assertThrows(
            SerializationException::class.java,
            {
                Json.decodeFromString(ImpactCallFrameDocument.serializer(), encoded)
            },
            "Bounded encoded input bytes=$bytes",
        )
    }

    @Test
    fun `988 expanded frame occurrences decode even when direct frames share one object`() {
        val frame = repeatedFrames(29)
        val decoded = Json.decodeFromString(ImpactCallFrameDocument.serializer(), encode(frame))
        val target = decoded.target as ImpactCallTargetDocument.Callback
        assertEquals(17, target.selected.callableTransfers.size)
    }

    @Test
    fun `1022 expanded frame occurrences exceed the document count limit`() {
        val encoded = encode(repeatedFrames(30))
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 1_500_000, "Bounded encoded input bytes=$bytes")
        assertThrows(
            SerializationException::class.java,
            {
                Json.decodeFromString(ImpactCallFrameDocument.serializer(), encoded)
            },
            "Bounded encoded input bytes=$bytes",
        )
    }

    private fun repeatedFrames(count: Int): ImpactCallFrameDocument {
        val f = CallbackIdentityFixture()
        val stack = BoundedProtocolList.create(List(count) { f.target.supplyingFrame.asFrame() }).value()
        val site = f.argument.copy(callStack = stack)
        val transfer = ImpactCompilerTransferDocument(site, site, ImpactTransferKindDocument.LOCAL_READ)
        val selected = f.selected.copy(callableTransfers = List(17) { transfer })
        return f.frame.copy(target = f.target.copy(selected = selected))
    }

    /** A malformed remote proof reaches the new frame route without recursive owner bindings. */
    private fun nestedFrames(depth: Int): ImpactCallFrameDocument {
        val f = CallbackIdentityFixture()
        var frame = f.frame
        repeat(depth - 1) {
            val source = f.argument.copy(callStack = BoundedProtocolList.create(listOf(frame)).value())
            val transfer = ImpactCompilerTransferDocument(source, f.argument, ImpactTransferKindDocument.LOCAL_READ)
            val selected = f.selected.copy(callableTransfers = listOf(transfer))
            frame = f.frame.copy(target = f.target.copy(selected = selected))
        }
        return frame
    }

    private fun encode(frame: ImpactCallFrameDocument): String =
        Json.encodeToString(ImpactCallFrameDocument.serializer(), frame)
}

@Serializable
private data class DefaultDirectFrameInput(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactCallOccurrenceDocument,
    val callee: ImpactExecutionReferenceDocument,
)

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
