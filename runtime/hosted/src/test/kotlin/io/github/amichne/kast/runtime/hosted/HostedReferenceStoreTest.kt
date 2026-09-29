package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecoding
import io.github.amichne.kast.query.protocol.CanonicalSelectorDecodingFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedReferenceStoreTest {
    @Test
    fun `escaped token store and transports remain terminal after retirement`() {
        val tokens = HostedReferenceTokens(ReadLimits.Default)
        val transport = tokens.transport()
        val canonical = text("exact:v3:owned")
        val handle = transport.issue(canonical).refined()
        tokens.retire()
        for (escaped in listOf(transport, tokens.transport())) {
            assertEquals(
                Refinement.Rejected(io.github.amichne.kast.query.protocol.QueryReferenceTransportFailure.UNAVAILABLE),
                escaped.issue(canonical),
            )
            assertEquals(
                CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE),
                escaped.restore(handle),
            )
            assertEquals(
                CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.UNAVAILABLE),
                escaped.restore(canonical),
            )
        }
    }

    @Test
    fun `equal transport revisions from a different semantic owner cannot replace state`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val first = io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture.create(root)
        val foreign = io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture.create(root)
        assertEquals(first.reference, foreign.reference)
        val store = HostedReferenceStore()
        val current = store.transport(first, ReadLimits.Default, IntellijReadObservation.None).refined()
        val handle = current.issue(text("exact:v3:owned")).refined()
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.WRONG_HOST),
            store.transport(foreign, ReadLimits.Default, IntellijReadObservation.None),
        )
        assertEquals(CanonicalSelectorDecoding.Decoded(text("exact:v3:owned")), current.restore(handle))
    }

    @Test
    fun `canonical live and compact references restore through the same authoritative codec unchanged`() {
        val fixture = io.github.amichne.kast.query.protocol.RelationPagingFixture.live()
        val transport = HostedReferenceTokens(ReadLimits.Default).transport()
        val references = io.github.amichne.kast.query.protocol.CanonicalQueryReferences(transport)
        val compact =
            (references.issueExact(fixture.selector)
                    as io.github.amichne.kast.query.protocol.ExactSelectorIssuance.Issued)
                .selector
        assertTrue(fixture.exact.value.startsWith("exact:v3:"))
        assertTrue(compact.value.startsWith("exact:v5:"))
        for (token in listOf(fixture.exact, compact)) {
            val restored = references.restoreExact(token, fixture.authority) as CanonicalSelectorDecoding.Decoded
            val encoded =
                io.github.amichne.kast.query.protocol.CanonicalSelectorCodec.encodeExact(restored.value)
                    as io.github.amichne.kast.query.protocol.CanonicalSelectorEncoding.Encoded
            assertEquals(fixture.exact, encoded.token)
            val other =
                io.github.amichne.kast.workspace.contract.SemanticReadLease(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/other-workspace")).refined(),
                    io.github.amichne.kast.kernel.EvidenceGeneration.parse(1).refined(),
                )
            assertTrue(references.restoreExact(token, other) is CanonicalSelectorDecoding.Rejected)
        }
        val candidate =
            references.issueRangeCandidate(fixture.authority, fixture.selector.file, 0, 1)
                as io.github.amichne.kast.query.protocol.CandidateSelectorTokenIssuance.Issued
        assertTrue(references.restoreExact(candidate.selector, fixture.authority) is CanonicalSelectorDecoding.Rejected)
    }

    @Test
    fun `short digest collision preserves existing authority and returns inline selector`() {
        val first = text("exact:v3:first-scoped-selector")
        val second = text("exact:v3:second-scoped-selector")
        val handle = io.github.amichne.kast.query.protocol.compactSymbolReference(first)
        val counts = Counts()
        val transport = HostedReferenceTokens(ReadLimits.Default) { handle }.transport(counts)
        assertEquals(handle.token, transport.issue(first).refined())
        assertEquals(second, transport.issue(second).refined())
        assertEquals(first, (transport.restore(handle.token) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(1, counts.values[IntellijReadCounter.REFERENCE_INLINE_COLLISION])
    }

    @Test
    fun `lookup preserves all original bytes and reuses the same compact identity`() {
        val counts = Counts()
        val transport = HostedReferenceTokens(ReadLimits.Default).transport(counts)
        val canonical = text("exact:v3:" + "a".repeat(3000))
        val compact = transport.issue(canonical).refined()
        assertEquals(31, compact.value.length)
        assertEquals(compact, transport.issue(canonical).refined())
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
        val handle = transport.issue(first).refined()
        assertEquals(second, transport.issue(second).refined())
        assertEquals(first, (transport.restore(handle) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(second, (transport.restore(second) as CanonicalSelectorDecoding.Decoded).value)
        assertEquals(1, counts.values[IntellijReadCounter.REFERENCE_INLINE_CAPACITY])
    }

    @Test
    fun `byte capacity is checked independently of entry capacity`() {
        val transport = HostedReferenceTokens(limits(ReadLimitParameter.HOST_REFERENCE_BYTES, 1)).transport()
        val canonical = text("exact:v3:original")
        assertEquals(canonical, transport.issue(canonical).refined())
    }

    @Test
    fun `unknown valid handles reject as unavailable and malformed handles retain syntax failure`() {
        val counts = Counts()
        val transport = HostedReferenceTokens(ReadLimits.Default).transport(counts)
        assertEquals(
            CanonicalSelectorDecodingFailure.UNAVAILABLE,
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
        val epochs =
            io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
            )
        val reference = epochs.admit()
        val first = store.transport(reference, ReadLimits.Default, IntellijReadObservation.None).refined()
        val handle = first.issue(text("exact:v3:one")).refined()
        val same = store.transport(reference, ReadLimits.Default, IntellijReadObservation.None).refined()
        assertTrue(same.restore(handle) is CanonicalSelectorDecoding.Decoded)
        val next =
            store
                .transport(
                    epochs.advance(),
                    ReadLimits.Default,
                    IntellijReadObservation.None,
                )
                .refined()
        assertEquals(
            CanonicalSelectorDecodingFailure.UNAVAILABLE,
            (next.restore(handle) as CanonicalSelectorDecoding.Rejected).failure,
        )
        val nextHandle = next.issue(text("exact:v3:two")).refined()
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.EPOCH_MOVED),
            store.transport(reference, ReadLimits.Default, IntellijReadObservation.None),
        )
        assertEquals(CanonicalSelectorDecoding.Decoded(text("exact:v3:two")), next.restore(nextHandle))
        store.dispose()
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.query.protocol.QueryReferenceTransportFailure.UNAVAILABLE),
            next.issue(text("exact:v3:late")),
        )
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.RETIRED),
            store.transport(epochs.admit(), ReadLimits.Default, IntellijReadObservation.None),
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.UNAVAILABLE,
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
