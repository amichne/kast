package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SemanticReadAuthorityTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/root")).value()
    private val host = IdeReadHostLifetime.fromBoundary(UUID.fromString("10000000-0000-0000-0000-000000000001"))
    private val source = FixtureEpochSource(FixtureEpochState.stable())
    private val owner = LiveSemanticReadOwner(root, host)

    @Test
    fun `published authority retains its generation without a live proof`() {
        val published = SemanticReadLease(root, EvidenceGeneration.parse(7).value())
        val authority: SemanticReadAuthority = published
        assertSame(published, assertInstanceOf(SemanticReadLease::class.java, authority))
        assertEquals(7L, published.generation.value)
    }

    @Test
    fun `unchanged epoch reuses the original authority and restores a detached reference`() {
        val original = admit()
        val current = admit()
        assertSame(original, current)
        assertSame(current, owner.restore(original.reference, freshness()).value())
        assertEquals(IdeReadContentView.SAVED_PSI_COMMITTED, current.reference.contentView)
        assertEquals(root, current.workspaceRoot)
    }

    @Test
    fun `source movement invalidates references even when earlier signal values return`() {
        val old = admit()
        source.result = Refinement.Refined(FixtureEpochState.stable().copy(psi = 2))
        assertEquals(LiveSemanticReadFailure.EPOCH_MOVED, owner.restore(old.reference, freshness()).failure())
        source.result = Refinement.Refined(FixtureEpochState.stable())
        assertEquals(LiveSemanticReadFailure.EPOCH_MOVED, owner.restore(old.reference, freshness()).failure())
    }

    @Test
    fun `foreign roots and incomparable sources cannot join the owner`() {
        val other = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/other")).value()
        assertEquals(
            LiveSemanticReadFailure.WRONG_ROOT,
            owner.admit(VfsPassiveReadCapability.issue(other, source.observeEpoch())).failure(),
        )
        admit()
        val foreign = FixtureEpochSource(FixtureEpochState.stable())
        assertEquals(
            LiveSemanticReadFailure.INCOMPARABLE_EPOCH,
            owner.admit(VfsPassiveReadCapability.issue(root, foreign.observeEpoch())).failure(),
        )
    }

    @Test
    fun `restart and forged transport fields cannot recreate read authority`() {
        val old = admit().reference
        val restarted =
            LiveSemanticReadOwner(
                root,
                IdeReadHostLifetime.fromBoundary(UUID.fromString("20000000-0000-0000-0000-000000000002")),
            )
        assertEquals(LiveSemanticReadFailure.WRONG_HOST, restarted.restore(old, freshness()).failure())
        val foreignRoot = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/foreign")).value()
        assertEquals(
            LiveSemanticReadFailure.WRONG_ROOT,
            owner.restore(old.copy(workspaceRoot = foreignRoot), freshness()).failure(),
        )
        assertEquals(
            LiveSemanticReadFailure.EPOCH_MOVED,
            owner.restore(old.copy(epoch = IdeReadEpochRevision.parse(99).value()), freshness()).failure(),
        )
        assertEquals(
            LiveSemanticReadFailure.REFERENCE_VERSION_UNSUPPORTED,
            owner.restore(old.copy(version = 0), freshness()).failure(),
        )
    }

    @Test
    fun `retirement is terminal for both admission and restoration`() {
        val old = admit().reference
        owner.retire()
        owner.retire()
        assertEquals(LiveSemanticReadFailure.RETIRED, owner.admit(freshness()).failure())
        assertEquals(LiveSemanticReadFailure.RETIRED, owner.restore(old, freshness()).failure())
    }

    @Test
    fun `authority identity distinguishes live epochs and requires published refinement`() {
        val published = SemanticReadLease(root, EvidenceGeneration.parse(7).value())
        assertEquals("7", published.identity.revisionKey.value)
        assertSame(published, published.requirePublished().value())
        val live = admit()
        assertEquals(PublishedReadAuthorityFailure.LIVE_AUTHORITY, live.requirePublished().failure())
        val identity = live.identity
        source.result = Refinement.Refined(FixtureEpochState.stable().copy(psi = 2))
        org.junit.jupiter.api.Assertions.assertNotEquals(identity, admit().identity)
        org.junit.jupiter.api.Assertions.assertNotEquals(published.identity.revisionKey, identity.revisionKey)
    }

    private fun freshness() = VfsPassiveReadCapability.issue(root, source.observeEpoch())

    private fun admit() = owner.admit(freshness()).value()

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement: $failure")
        }

    private fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
