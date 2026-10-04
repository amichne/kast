package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.NamedGradleModelObservation
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScope
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedNamedGradleSourceScopeTest {
    @Test
    fun `repeated reads retain the original scope but validate each read`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val scope = emptyScope()
        val retained = HostedNamedGradleSourceScope()
        var captures = 0
        var validations = 0
        repeat(3) {
            val result =
                retained.capture(
                    epoch = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch,
                    limits = ReadLimits.Default,
                    observation = IntellijReadObservation.None,
                    validate = {
                        validations++
                        Refinement.Refined(Unit)
                    },
                    observe = {
                        captures++
                        Refinement.Refined(scope)
                    },
                )
            assertSame(scope, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(result).value)
        }
        assertEquals(1, captures)
        assertEquals(4, validations)
    }

    @Test
    fun `moved and incomparable epochs cannot reuse the prior model`() = runTest {
        var state = 1
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(state) }
        val other = ProjectReadEpoch.Source.create { Refinement.Refined(state) }
        val retained = HostedNamedGradleSourceScope()
        val prior = emptyScope()
        val moved = emptyScope()
        val foreign = emptyScope()
        var captures = 0
        suspend fun read(source: ProjectReadEpoch.Source<Int>, expected: NamedGradleSourceScope) {
            val result =
                retained.capture(
                    epoch(source),
                    ReadLimits.Default,
                    IntellijReadObservation.None,
                    { Refinement.Refined(Unit) },
                    {
                        captures++
                        Refinement.Refined(expected)
                    },
                )
            assertSame(expected, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(result).value)
        }
        read(source, prior)
        state++
        read(source, moved)
        read(other, foreign)
        assertEquals(3, captures)
    }

    @Test
    fun `a different admitted limit policy requires recapture`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val smaller =
            assertInstanceOf<Refinement.Refined<ReadLimits>>(
                    ReadLimits.resolve(environment = mapOf("KAST_READ_MODEL_MODULES" to "128"))
                )
                .value
        val rejection =
            Refinement.Rejected(HostedQueryFailure.NamedSourceScope(NamedGradleSourceScopeFailure.CAPTURE_LIMIT))
        var captures = 0
        retained.capture(
            epoch(source),
            ReadLimits.Default,
            IntellijReadObservation.None,
            { Refinement.Refined(Unit) },
            {
                captures++
                Refinement.Refined(emptyScope())
            },
        )
        val result =
            retained.capture(
                epoch(source),
                smaller,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                },
            )
        assertEquals(rejection, result)
        assertEquals(2, captures)
    }

    @Test
    fun `rejected captures are retried without retaining failure`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val rejection =
            Refinement.Rejected(HostedQueryFailure.NamedSourceScope(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE))
        var captures = 0
        val failed =
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    Refinement.Rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                },
            )
        assertEquals(rejection, failed)
        val scope = emptyScope()
        val recovered =
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    Refinement.Refined(scope)
                },
            )
        assertSame(scope, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(recovered).value)
        assertEquals(2, captures)
    }

    @Test
    fun `freshness rejection cannot serve or retain a captured model`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val rejection = Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        var validations = 0
        var captures = 0
        val failed =
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { if (++validations == 2) rejection else Refinement.Refined(Unit) },
                {
                    captures++
                    Refinement.Refined(emptyScope())
                },
            )
        assertEquals(rejection, failed)
        retained.capture(
            epoch(source),
            ReadLimits.Default,
            IntellijReadObservation.None,
            { Refinement.Refined(Unit) },
            {
                captures++
                Refinement.Refined(emptyScope())
            },
        )
        val unavailable =
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { rejection },
                { error("rejected read must not capture") },
            )
        assertEquals(rejection, unavailable)
        retained.capture(
            epoch(source),
            ReadLimits.Default,
            IntellijReadObservation.None,
            { Refinement.Refined(Unit) },
            {
                captures++
                Refinement.Refined(emptyScope())
            },
        )
        assertEquals(3, captures)
    }

    @Test
    fun `retained reads explicitly observe zero model enumeration`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val counts = linkedMapOf<IntellijReadCounter, Int>()
        val observation =
            object : IntellijReadObservation by IntellijReadObservation.None {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    assertEquals(IntellijReadContributor.NONE, contributor)
                    check(counter !in counts)
                    counts[counter] = amount
                }
            }
        retained.capture(
            epoch(source),
            ReadLimits.Default,
            IntellijReadObservation.None,
            { Refinement.Refined(Unit) },
            { Refinement.Refined(emptyScope()) },
        )
        retained.capture(
            epoch(source),
            ReadLimits.Default,
            observation,
            { Refinement.Refined(Unit) },
            { error("retained read must not enumerate") },
        )
        assertEquals(
            mapOf(
                IntellijReadCounter.IMPORTED_PROJECTS to 0,
                IntellijReadCounter.IDEA_MODULES to 0,
                IntellijReadCounter.SELECTED_GRADLE_MODULES to 0,
                IntellijReadCounter.FOREIGN_GRADLE_MODULES to 0,
                IntellijReadCounter.SOURCE_ROOTS to 0,
            ),
            counts,
        )
    }

    @Test
    fun `concurrent reads share one completed capture`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = emptyScope()
        var captures = 0
        val first = async {
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    entered.complete(Unit)
                    release.await()
                    Refinement.Refined(scope)
                },
            )
        }
        entered.await()
        val second = async {
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                { error("second read must retain the completed capture") },
            )
        }
        release.complete(Unit)
        assertSame(scope, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(first.await()).value)
        assertSame(scope, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(second.await()).value)
        assertEquals(1, captures)
    }

    @Test
    fun `cancellation releases capture ownership without publishing a model`() = runTest {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val retained = HostedNamedGradleSourceScope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var captures = 0
        val first = async {
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    entered.complete(Unit)
                    release.await()
                    Refinement.Refined(emptyScope())
                },
            )
        }
        entered.await()
        first.cancelAndJoin()
        val scope = emptyScope()
        val recovered =
            retained.capture(
                epoch(source),
                ReadLimits.Default,
                IntellijReadObservation.None,
                { Refinement.Refined(Unit) },
                {
                    captures++
                    Refinement.Refined(scope)
                },
            )
        assertSame(scope, assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(recovered).value)
        assertEquals(2, captures)
    }

    private fun epoch(source: ProjectReadEpoch.Source<Int>): ProjectReadEpoch<*> =
        assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch

    private fun emptyScope(): NamedGradleSourceScope {
        val root =
            assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(
                    CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace"))
                )
                .value
        return assertInstanceOf<Refinement.Refined<NamedGradleSourceScope>>(
                NamedGradleSourceScope.admit(root, NamedGradleModelObservation.Captured(emptyList()), emptyList())
            )
            .value
    }
}
