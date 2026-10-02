package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Native callback concurrency, stopping and finite failure mapping use the production capture boundary. */
class IntellijTextOccurrenceCollectorTest {
    @Test
    fun `callbacks are bounded and observation is safe with concurrent native delivery`() {
        val element =
            Proxy.newProxyInstance(PsiElement::class.java.classLoader, arrayOf(PsiElement::class.java)) { _, _, _ ->
                error("Callback capture must not inspect PSI")
            } as PsiElement
        val projected = mutableSetOf<Int>()
        val qualifications = mutableSetOf<SymbolDiscoveryQualification>()
        val active = AtomicInteger()
        var observed = 0
        collectTextDiscoveryOccurrences(
            workLimit = WorkUnitLimit.parse(250).refined(),
            observe = {
                assertEquals(1, active.incrementAndGet(), "Observation state must not overlap")
                observed += 1
                active.decrementAndGet()
                true
            },
            qualify = qualifications::add,
            process = { accept ->
                val pool = Executors.newFixedThreadPool(8)
                val start = CountDownLatch(1)
                try {
                    val futures =
                        (0 until 8).map { worker ->
                            pool.submit<Boolean> {
                                start.await()
                                (0 until 100).all { ordinal -> accept(element, worker * 100 + ordinal) }
                            }
                        }
                    start.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }.all { it }
                } finally {
                    pool.shutdownNow()
                }
            },
            project = { _, offset ->
                assertTrue(projected.add(offset))
                true
            },
        )
        assertEquals(250, projected.size)
        assertTrue(observed >= 250)
        assertEquals(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED), qualifications)
    }

    @Test
    fun `native provider indexing failure and cancellation remain distinct`(@TempDir home: Path) {
        withTextDiscoveryParser(home) { _ ->
            val observations =
                mutableListOf<io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure>()
            val observer =
                object : io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation {
                    override fun count(
                        counter: io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter,
                        contributor: io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor,
                        amount: Int,
                    ) = Unit

                    override fun terminated(
                        reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
                        contributor: io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor,
                    ) = Unit

                    override fun unexpected(
                        failure: io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
                    ) {
                        observations += failure
                    }
                }
            fun capture(failure: RuntimeException): Set<SymbolDiscoveryQualification> {
                val qualifications = mutableSetOf<SymbolDiscoveryQualification>()
                val captured =
                    collectTextDiscoveryOccurrences(
                        WorkUnitLimit.parse(1).refined(),
                        { true },
                        qualifications::add,
                        process = { throw failure },
                        project = { _, _ -> error("Failed provider captured no candidate") },
                        observation = observer,
                    )
                assertEquals(0, captured)
                return qualifications
            }
            assertEquals(
                setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE),
                capture(IllegalStateException("bounded provider failure")),
            )
            assertEquals(1, observations.size)
            assertEquals(
                setOf(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION),
                capture(com.intellij.openapi.project.IndexNotReadyException.create()),
            )
            assertEquals(1, observations.size)
            assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
                capture(com.intellij.openapi.progress.ProcessCanceledException())
            }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                capture(java.util.concurrent.CancellationException())
            }
        }
    }

    @Test
    fun `large positive elapsed budgets retain their saturated duration`() {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val request =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(lease, scope),
                SymbolDiscoveryTarget.TextDeclarations(SymbolDiscoveryWord.parse("launchd").refined()),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(1).refined(),
                        WorkUnitLimit.parse(1).refined(),
                        ElapsedTimeLimitMillis.parse(Long.MAX_VALUE).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(1000).refined(),
                ),
            )
        var now = 0L
        val allowance = IntellijDeclarationDiscoveryAllowance(request, IntellijReadNanoClock { now })
        now = 1L
        assertFalse(allowance.expired())
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
