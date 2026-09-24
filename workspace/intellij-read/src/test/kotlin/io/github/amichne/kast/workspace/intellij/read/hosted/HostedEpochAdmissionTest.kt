package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.admitVfsPassiveReadObservation
import java.nio.file.Path
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedEpochAdmissionTest {
    private val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
    private val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }

    @Test
    fun `preempted and moved observations retry until one current epoch is admitted`() = runTest {
        var observations = 0
        var admissions = 0
        var pauses = 0
        val counters = mutableMapOf<IntellijReadCounter, Int>()
        val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    counters[counter] = counters.getOrDefault(counter, 0) + amount
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
            }
        val result =
            awaitHostedEpochAdmission(
                observe = {
                    if (++observations == 1)
                        ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.ReadPreempted)
                    else source.observe()
                },
                admit = { epoch ->
                    if (++admissions == 1) VfsPassiveReadAdmission.Rejected(VfsPassiveReadAdmissionFailure.Moved)
                    else admitVfsPassiveReadObservation(root, epoch, source.observe())
                },
                observation = observation,
                pause = { pauses++ },
            )
        val admitted = (result as Refinement.Refined).value
        assertEquals(3, observations)
        assertEquals(2, admissions)
        assertEquals(2, pauses)
        assertEquals(
            mapOf(IntellijReadCounter.EPOCH_READ_PREEMPTIONS to 1, IntellijReadCounter.EPOCH_MOVED_RETRIES to 1),
            counters,
        )
        assertEquals(root, admitted.freshness.canonicalRoot)
        assertEquals(ProjectReadEpochRelation.SAME, admitted.epoch.relationTo(admitted.freshness.admittedEpoch))
    }

    @Test
    fun `nontransient epoch failure remains a finite rejection`() = runTest {
        var admitted = false
        val result =
            awaitHostedEpochAdmission(
                observe = { ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.DumbMode) },
                admit = {
                    admitted = true
                    error("admission must not run")
                },
                pause = { error("nontransient failure must not wait") },
            )
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.ReadEpoch(ProjectReadEpochObservationFailure.DumbMode)),
            result,
        )
        assertEquals(false, admitted)
    }

    @Test
    fun `persistent preemption obeys the enclosing deadline`() = runTest {
        var observations = 0
        val failure = runCatching {
            withTimeout(50) {
                awaitHostedEpochAdmission(
                    observe = {
                        observations++
                        ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.ReadPreempted)
                    },
                    admit = { error("no epoch was observed") },
                )
            }
        }
            .exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(observations > 1)
    }
}
