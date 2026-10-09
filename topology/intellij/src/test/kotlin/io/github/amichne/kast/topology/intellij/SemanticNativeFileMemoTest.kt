package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Real owned stream hashing through the production memo; no native VFS/SDK assertion. */
internal class SemanticNativeFileMemoTest : SemanticReadInputFixture() {
    private val identity = SemanticNativeFileIdentity("file:///owned/input")

    @Test
    fun `each hash kind has a lifetime capacity and an existing entry does not spend another slot`() {
        val limits =
            io.github.amichne.kast.kernel.ReadLimits.resolve(
                    properties =
                        mapOf(io.github.amichne.kast.kernel.ReadLimitParameter.DISCOVERY_FILES.propertyKey to "1")
                )
                .value()
        val memo = SemanticNativeFileMemo(limits)
        memo.hash(identity, budget()) { Refinement.Refined(hash) }.value()
        memo.tree(identity, budget()) { Refinement.Refined(hash) }.value()
        memo.hash(identity, budget()) { unexpected() }.value()
        memo.tree(identity, budget()) { unexpected() }.value()
        val different = SemanticNativeFileIdentity("file:///owned/other")
        val rejected = Refinement.Rejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        assertEquals(rejected, memo.hash(different, budget()) { unexpected() })
        assertEquals(rejected, memo.tree(different, budget()) { unexpected() })
    }

    @Test
    fun `successful shared input reads physical bytes only once and a fresh native scope reads them again`() {
        val memo = SemanticNativeFileMemo()
        var opens = 0
        var reads = 0
        fun capture(): SemanticCapture<io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash> {
            opens++
            val input =
                object : ByteArrayInputStream("abc".toByteArray()) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        reads++
                        return super.read(bytes, offset, length)
                    }
                }
            return input.use { hashSemanticInput(it, budget()) }
        }
        val first = memo.hash(identity, budget(), ::capture).value()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first.value)
        assertEquals(first, memo.hash(identity, budget(), ::capture).value())
        assertEquals(1, opens)
        assertEquals(2, reads)
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS])
        memo.finishNativeRead()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED),
            memo.hash(identity, budget()) { unexpected() },
        )
        SemanticNativeFileMemo().hash(identity, budget(), ::capture).value()
        assertEquals(2, opens)
        assertEquals(4, reads)
    }

    @Test
    fun `file and tree identities retain distinct proofs and share only their completed kind`() {
        val memo = SemanticNativeFileMemo()
        val file = memo.hash(identity, budget()) { Refinement.Refined(hash) }.value()
        val treeHash =
            io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash.parse("b".repeat(64)).value()
        val tree = memo.tree(identity, budget()) { Refinement.Refined(treeHash) }.value()
        assertEquals(file, memo.hash(identity, budget()) { unexpected() }.value())
        assertEquals(tree, memo.tree(identity, budget()) { unexpected() }.value())
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_TREE_MEMO_HITS])
    }

    @Test
    fun `partial hashing and input failure cannot publish a completed memo value`() {
        val memo = SemanticNativeFileMemo()
        repeat(2) {
            assertEquals(
                Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED),
                memo.hash(identity, budget()) {
                    hashSemanticInput("abc".byteInputStream(), budget(1))
                },
            )
        }
        val failure = IOException("Owned stream failure")
        assertSame(failure, assertThrows(IOException::class.java) { memo.hash(identity, budget()) { throw failure } })
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS])
        memo.hash(identity, budget()) { Refinement.Refined(hash) }.value()
    }

    @Test
    fun `current expiry and cancellation precede a memo hit`() {
        val memo = SemanticNativeFileMemo()
        memo.hash(identity, budget()) { Refinement.Refined(hash) }.value()
        val expired = budget()
        now = 100_000_000
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED),
            memo.hash(identity, expired) { unexpected() },
        )
        val cancelled = CancellationException("Native read cancelled")
        checkCanceled = { throw cancelled }
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) { memo.hash(identity, budget()) { unexpected() } },
        )
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS])
    }

    private fun unexpected(): Nothing = throw AssertionError("Unexpected input effect")
}
