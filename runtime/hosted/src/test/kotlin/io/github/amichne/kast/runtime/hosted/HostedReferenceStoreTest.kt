package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecodingFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedReferenceStoreTest {
    @Test
    fun `lookup preserves all original bytes and reuses the same compact identity`() {
        val counts = Counts()
        val transport = HostedReferenceTokens(ReadLimits.Default).transport(counts)
        val canonical = text("exact:v3:" + "a".repeat(3000))
        val compact = transport.issue(canonical)
        assertEquals(73, compact.value.length)
        assertEquals(compact, transport.issue(canonical))
        assertEquals(canonical, (transport.restore(compact) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(2, counts.values[IntellijReadCounter.REFERENCE_HANDLES_ISSUED])
        assertEquals(1, counts.values[IntellijReadCounter.REFERENCE_HANDLES_RESTORED])
    }

    @Test
    fun `entry capacity preserves issued handles and returns an inline representation for overflow`() {
        val counts = Counts()
        val limits = limits(ReadLimitParameter.HOST_REFERENCE_ENTRIES, 1)
        val transport = HostedReferenceTokens(limits).transport(counts)
        val first = text("candidate:v3:first")
        val second = text("candidate:v3:second")
        val handle = transport.issue(first)
        assertEquals(second, transport.issue(second))
        assertEquals(first, (transport.restore(handle) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(second, (transport.restore(second) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(1, counts.values[IntellijReadCounter.REFERENCE_INLINE_CAPACITY])
    }

    @Test
    fun `byte capacity is checked independently of entry capacity`() {
        val transport = HostedReferenceTokens(limits(ReadLimitParameter.HOST_REFERENCE_BYTES, 1)).transport()
        val canonical = text("exact:v3:original")
        assertEquals(canonical, transport.issue(canonical))
    }

    @Test
    fun `unknown valid handles reject as stale and malformed handles retain syntax failure`() {
        val counts = Counts()
        val transport = HostedReferenceTokens(ReadLimits.Default).transport(counts)
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (transport.restore(text("exact:v4:" + "0".repeat(64))) as CanonicalSelectorDecoding.Rejected).failure,
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE,
            (transport.restore(text("exact:v4:bad")) as CanonicalSelectorDecoding.Rejected).failure,
        )
        assertEquals(2, counts.values[IntellijReadCounter.REFERENCE_HANDLES_REJECTED])
    }

    @Test
    fun `epoch change and host disposal release retained tokens`() {
        val store = HostedReferenceStore()
        val reference =
            LiveSemanticReadReference(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
                IdeReadEpochRevision.parse(1).refined(),
                IdeReadContentView.SAVED_PSI_COMMITTED,
                LiveSemanticReadReference.VERSION,
            )
        val first = store.transport(reference, ReadLimits.Default, IntellijReadObservation.None)
        val handle = first.issue(text("exact:v3:one"))
        val same = store.transport(reference, ReadLimits.Default, IntellijReadObservation.None)
        assertTrue(same.restore(handle) is CanonicalSelectorDecoding.Decoded)
        val next =
            store.transport(
                reference.copy(epoch = IdeReadEpochRevision.parse(2).refined()),
                ReadLimits.Default,
                IntellijReadObservation.None,
            )
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (next.restore(handle) as CanonicalSelectorDecoding.Rejected).failure,
        )
        val nextHandle = next.issue(text("exact:v3:two"))
        store.dispose()
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (next.restore(nextHandle) as CanonicalSelectorDecoding.Rejected).failure,
        )
    }

    private class Counts : IntellijReadObservation {
        val values = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            values[counter] = values.getOrDefault(counter, 0) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    private fun limits(parameter: ReadLimitParameter, value: Int) =
        ReadLimits.resolve(properties = mapOf(parameter.propertyKey to value.toString())).refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun <V, F> Refinement<V, F>.refined(): V = (this as Refinement.Refined).value
}
