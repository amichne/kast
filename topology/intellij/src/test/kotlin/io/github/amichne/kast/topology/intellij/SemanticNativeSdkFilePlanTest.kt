package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Real planner, ownership, tree framing and stream hashing; only VFS/document observations are supplied. */
internal class SemanticNativeSdkFilePlanTest : SemanticReadInputFixture() {
    private var documentCalls = 0
    private var documentState = SemanticNativeDocumentState.CLEAN
    private val documents = SemanticNativeDocumentPort {
        documentCalls++
        documentState
    }

    private fun files(budget: DependencyCaptureBudget, memo: SemanticNativeFileMemo = SemanticNativeFileMemo()) =
        SemanticNativeFiles(ReadLimits.Default, budget, memo, documents)

    @Test
    fun `invalid metadata preserves its typed failure without payload observations`() {
        val leaf = SdkPlanTestFile("/sdk/a", valid = false)
        val budget = budget()
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE),
            files(budget).sdkRoots(listOf(leaf), SemanticInputDigest()),
        )
        assertEquals(1L, budget.cost().workUnits)
        assertEquals(0, leaf.opens)
        assertEquals(0, documentCalls)
    }

    @Test
    fun `unsupported providers preserve their typed failure without payload observations`() {
        val leaf = SdkPlanTestFile("/sdk/a", providerProtocol = "unsupported")
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_PROVIDER_UNSUPPORTED),
            files(budget()).sdkRoots(listOf(leaf), SemanticInputDigest()),
        )
        assertEquals(0, leaf.opens)
    }

    @Test
    fun `pending root plans obey the complete tree memo capacity before another walk`() {
        val limits =
            ReadLimits.resolve(
                    properties =
                        mapOf(io.github.amichne.kast.kernel.ReadLimitParameter.DISCOVERY_FILES.propertyKey to "1")
                )
                .value()
        val a = SdkPlanTestFile("/a", entries = emptyList())
        val b = SdkPlanTestFile("/b", entries = emptyList())
        val budget = budget()
        val files = SemanticNativeFiles(limits, budget, SemanticNativeFileMemo(limits), documents)
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED),
            files.sdkRoots(listOf(a, b), SemanticInputDigest()),
        )
        assertEquals(1L, budget.cost().workUnits)
        assertEquals(1, a.visits)
        assertEquals(0, b.visits)
    }

    @Test
    fun `complete file memo capacity constrains new hashes before payload work`() {
        val limits =
            ReadLimits.resolve(
                    properties =
                        mapOf(io.github.amichne.kast.kernel.ReadLimitParameter.DISCOVERY_FILES.propertyKey to "1")
                )
                .value()
        val memo = SemanticNativeFileMemo(limits)
        memo.hash(SemanticNativeFileIdentity("file:///existing"), budget()) { Refinement.Refined(hash) }.value()
        val leaf = SdkPlanTestFile("/new")
        val files = SemanticNativeFiles(limits, budget(), memo, documents)
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED),
            files.sdkRoots(listOf(leaf), SemanticInputDigest()),
        )
        assertEquals(0, leaf.opens)
        assertEquals(0, documentCalls)
    }

    @Test
    fun `infeasible minimum avoids physical observations and debits actual metadata only`() {
        val leaves = (1..3).map { SdkPlanTestFile("/sdk/$it") }
        val root = SdkPlanTestFile("/sdk", entries = leaves)
        val budget = budget(4)
        val memo = SemanticNativeFileMemo()
        val costs = mutableListOf<Long>()
        val result =
            observeDependencyCaptureCost(budget, { costs.add(it.workUnits) }) {
                files(budget, memo).sdkRoots(listOf(root), SemanticInputDigest())
            }
        assertEquals(Refinement.Rejected(SemanticDependencyCaptureFailure.MINIMUM_HASH_WORK_UNAVAILABLE), result)
        assertEquals(listOf(3L), costs)
        assertEquals(2, counts[IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS])
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_SDK_FILE_PLANS_COMPLETED])
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED])
        assertEquals(0, documentCalls)
        assertEquals(0, leaves.sumOf { it.opens })
        assertEquals(0, leaves.sumOf { it.reads })
        leaves.forEach {
            assertEquals(
                Refinement.Refined(SemanticNativeFileMemo.HashPresence.Missing),
                memo.hashPresence(SemanticNativeFileIdentity(it.url)),
            )
        }
    }

    @Test
    fun `feasible capture walks once and preserves framing and empty file hashes`() {
        val a = SdkPlanTestFile("/sdk/a", "abc".toByteArray())
        val b = SdkPlanTestFile("/sdk/nested/b")
        val nested = SdkPlanTestFile("/sdk/nested", entries = listOf(b))
        val root = SdkPlanTestFile("/sdk", entries = listOf(nested, a))
        val budget = budget(7)
        val digest = SemanticInputDigest()
        files(budget).sdkRoots(listOf(root), digest).value()
        val tree = framed("FILE", a.url, sha(a.bytes), "FILE", b.url, sha(b.bytes))
        assertEquals(framed("1", "ROOT", root.url, tree), digest.finish().value)
        assertEquals(7L, budget.cost().workUnits)
        assertEquals(listOf(1, 1, 1, 1), listOf(root.visits, a.visits, nested.visits, b.visits))
        assertEquals(2, root.childrenCalls + nested.childrenCalls)
        assertEquals(3, a.reads + b.reads)
        assertEquals(2, a.closes + b.closes)
        assertEquals(2, documentCalls)
        assertEquals(3, counts[IntellijReadCounter.DEPENDENCY_HASH_BYTES_READ])
        assertEquals(2, counts[IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED])
    }

    @Test
    fun `multiple empty complete roots remain complete when metadata consumes the exact grant`() {
        val a = SdkPlanTestFile("/empty/a", entries = emptyList())
        val b = SdkPlanTestFile("/empty/b", entries = emptyList())
        val budget = budget(2)
        val digest = SemanticInputDigest()
        files(budget).sdkRoots(listOf(a, b), digest).value()
        assertEquals(
            framed("2", "ROOT", a.url, sha(byteArrayOf()), "ROOT", b.url, sha(byteArrayOf())),
            digest.finish().value,
        )
        assertEquals(2L, budget.cost().workUnits)
        assertEquals(0, documentCalls)
    }

    @Test
    fun `duplicate roots and overlapping leaves retain root order and reserve a missing identity only once`() {
        val leaf = SdkPlanTestFile("/shared/leaf", "abc".toByteArray())
        val a = SdkPlanTestFile("/sdk/a", entries = listOf(leaf))
        val b = SdkPlanTestFile("/sdk/b", entries = listOf(leaf))
        val budget = budget(7)
        val digest = SemanticInputDigest()
        files(budget).sdkRoots(listOf(a, a, b), digest).value()
        val tree = framed("FILE", leaf.url, sha(leaf.bytes))
        assertEquals(framed("3", "ROOT", a.url, tree, "ROOT", a.url, tree, "ROOT", b.url, tree), digest.finish().value)
        assertEquals(6L, budget.cost().workUnits)
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS])
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS])
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_TREE_MEMO_HITS])
        assertEquals(1, a.childrenCalls)
        assertEquals(1, leaf.opens)
    }

    @Test
    fun `completed read-local hash does not require another physical read and a cached tree skips metadata`() {
        val leaf = SdkPlanTestFile("/sdk/a", "abc".toByteArray())
        val root = SdkPlanTestFile("/sdk", entries = listOf(leaf))
        val memo = SemanticNativeFileMemo()
        files(budget(), memo).hash(leaf).value()
        val budget = budget(3)
        files(budget, memo).sdkRoots(listOf(root), SemanticInputDigest()).value()
        files(budget(1), memo).sdkRoots(listOf(root), SemanticInputDigest()).value()
        assertEquals(2L, budget.cost().workUnits)
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS])
        assertEquals(1, leaf.opens)
        assertEquals(1, root.childrenCalls)
        assertEquals(1, counts[IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS])
    }

    @Test
    fun `a feasible lower bound is not a success promise and partial hashing retains cost without publishing proof`() {
        val leaf = SdkPlanTestFile("/sdk/a", ByteArray(8193) { 42 })
        val root = SdkPlanTestFile("/sdk", entries = listOf(leaf))
        val memo = SemanticNativeFileMemo()
        val budget = budget(4)
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED),
            files(budget, memo).sdkRoots(listOf(root), SemanticInputDigest()),
        )
        assertEquals(4L, budget.cost().workUnits)
        assertEquals(8193, counts[IntellijReadCounter.DEPENDENCY_HASH_BYTES_READ])
        assertEquals(0, counts[IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED])
        assertEquals(
            Refinement.Refined(SemanticNativeFileMemo.HashPresence.Missing),
            memo.hashPresence(SemanticNativeFileIdentity(leaf.url)),
        )
        assertEquals(1, leaf.closes)
        files(budget(), memo).sdkRoots(listOf(root), SemanticInputDigest()).value()
        assertEquals(2, leaf.opens)
        assertEquals(2, root.childrenCalls)
    }

    @Test
    fun `fresh native reads hash changed external content again without a workspace epoch change`() {
        val leaf = SdkPlanTestFile("/external/sdk/a", "before".toByteArray())
        val first = SemanticInputDigest()
        val firstMemo = SemanticNativeFileMemo()
        files(budget(), firstMemo).sdkRoots(listOf(leaf), first).value()
        firstMemo.finishNativeRead()
        leaf.bytes = "after".toByteArray()
        val second = SemanticInputDigest()
        files(budget()).sdkRoots(listOf(leaf), second).value()
        assertNotEquals(first.finish(), second.finish())
        assertEquals(2, leaf.opens)
        assertEquals(2, counts[IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED])
    }

    @Test
    fun `native cancellation and the original elapsed deadline stop planning before payload work`() {
        val leaf = SdkPlanTestFile("/sdk/a")
        val root = SdkPlanTestFile("/sdk", entries = listOf(leaf))
        val timed = budget()
        root.beforeChildren = { now = 100_000_000 }
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED),
            files(timed).sdkRoots(listOf(root), SemanticInputDigest()),
        )
        assertEquals(1L, timed.cost().workUnits)
        now = 0
        val cancelled = CancellationException("Owned cancellation")
        root.beforeChildren = { checkCanceled = { throw cancelled } }
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                files(budget()).sdkRoots(listOf(root), SemanticInputDigest())
            },
        )
        assertEquals(0, leaf.opens)
        assertEquals(0, documentCalls)
    }

    @Test
    fun `dirty and uncommitted observations reject a feasible plan before streams and preserve their finite cause`() {
        val leaf = SdkPlanTestFile("/sdk/a")
        for ((state, failure) in
            listOf(
                SemanticNativeDocumentState.DIRTY to SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_DIRTY,
                SemanticNativeDocumentState.UNCOMMITTED to SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_UNCOMMITTED,
            )) {
            documentState = state
            assertEquals(Refinement.Rejected(failure), files(budget()).sdkRoots(listOf(leaf), SemanticInputDigest()))
        }
        assertEquals(0, leaf.opens)
        assertEquals(2, documentCalls)
    }

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun framed(vararg parts: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return HexFormat.of().formatHex(hash.digest())
    }
}
