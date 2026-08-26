package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class VfsPassiveAdmissionNegativeTest {
    @Test
    fun `admitted project owns the typed freshness transition`() {
        val transitions = AdmittedIdeProject::class.java.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                !method.isSynthetic &&
                method.name == "admitVfsPassiveRead"
        }
        val transition = transitions.singleOrNull()
        if (transition == null ||
            !transition.parameterTypes.contentEquals(arrayOf(ProjectReadEpoch::class.java)) ||
            transition.returnType.name !=
            "io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission"
        ) {
            fail<Unit>("KVP-019 missing VfsPassiveReadCapability admission transition")
        }
    }

    @Test
    fun `moved and incomparable epochs fail closed`() {
        val admittedSource = RecordingFreshnessEpochSource()
        val admittedProject = admittedFreshnessProject(admittedSource)
        val expectedEpoch = admittedSource.observeEpoch()
        admittedSource.observation = { Refinement.Refined(2) }

        assertEquals(
            VfsPassiveReadAdmissionFailure.Moved,
            rejectedFreshnessFailure(admittedProject.admitVfsPassiveRead(expectedEpoch)),
        )
        assertEquals(2, admittedSource.observationCount)

        val otherSource = RecordingFreshnessEpochSource()
        val otherEpoch = otherSource.observeEpoch()
        admittedSource.observation = { Refinement.Refined(1) }
        assertEquals(
            VfsPassiveReadAdmissionFailure.Incomparable,
            rejectedFreshnessFailure(admittedProject.admitVfsPassiveRead(otherEpoch)),
        )
        assertEquals(3, admittedSource.observationCount)
        assertEquals(1, otherSource.observationCount)
    }

    @Test
    fun `disposed and dumb observations retain their exact closed failures`() {
        assertMappedFailure(
            ProjectReadEpochObservationFailure.ProjectDisposed,
            VfsPassiveReadAdmissionFailure.ProjectDisposed,
        )
        assertMappedFailure(
            ProjectReadEpochObservationFailure.DumbMode,
            VfsPassiveReadAdmissionFailure.DumbMode,
        )
    }

    @Test
    fun `all remaining observation failures remain exact unavailable data`() {
        val failures = listOf(
            ProjectReadEpochObservationFailure.WrongThread to
                VfsPassiveReadUnavailableCause.WrongThread,
            ProjectReadEpochObservationFailure.ProjectNotOpen to
                VfsPassiveReadUnavailableCause.ProjectNotOpen,
            ProjectReadEpochObservationFailure.ProjectNotInitialized to
                VfsPassiveReadUnavailableCause.ProjectNotInitialized,
            ProjectReadEpochObservationFailure.ProjectRootUnavailable to
                VfsPassiveReadUnavailableCause.ProjectRootUnavailable,
            ProjectReadEpochObservationFailure.ProjectRootMalformed to
                VfsPassiveReadUnavailableCause.ProjectRootMalformed,
            ProjectReadEpochObservationFailure.GradleModelUnavailable to
                VfsPassiveReadUnavailableCause.GradleModelUnavailable,
            ProjectReadEpochObservationFailure.GradleModelIncomplete to
                VfsPassiveReadUnavailableCause.GradleModelIncomplete,
            ProjectReadEpochObservationFailure.GradleModelAmbiguous to
                VfsPassiveReadUnavailableCause.GradleModelAmbiguous,
            ProjectReadEpochObservationFailure.GradleRootUnavailable to
                VfsPassiveReadUnavailableCause.GradleRootUnavailable,
            ProjectReadEpochObservationFailure.GradleRootMalformed to
                VfsPassiveReadUnavailableCause.GradleRootMalformed,
            ProjectReadEpochObservationFailure.ImportTimestampsIncoherent to
                VfsPassiveReadUnavailableCause.ImportTimestampsIncoherent,
            ProjectReadEpochObservationFailure.VfsBatchLimitExceeded to
                VfsPassiveReadUnavailableCause.VfsBatchLimitExceeded,
            ProjectReadEpochObservationFailure.VfsPathMalformed to
                VfsPassiveReadUnavailableCause.VfsPathMalformed,
            ProjectReadEpochObservationFailure.SignalExhausted to
                VfsPassiveReadUnavailableCause.SignalExhausted,
            ProjectReadEpochObservationFailure.ReadPreempted to
                VfsPassiveReadUnavailableCause.ReadPreempted,
        ) + ProjectReadEpochObservationStage.entries.map { stage ->
            ProjectReadEpochObservationFailure.ObservationFailed(stage) to
                VfsPassiveReadUnavailableCause.ObservationFailed(stage)
        }

        failures.forEach { (failure, unavailableCause) ->
            assertMappedFailure(
                failure,
                VfsPassiveReadAdmissionFailure.Unavailable(unavailableCause),
            )
        }
    }

    @Test
    fun `platform cancellation remains platform cancellation`() {
        val source = RecordingFreshnessEpochSource()
        val admittedProject = admittedFreshnessProject(source)
        val expectedEpoch = source.observeEpoch()
        source.observation = { throw ProcessCanceledException() }

        assertThrows(ProcessCanceledException::class.java) {
            admittedProject.admitVfsPassiveRead(expectedEpoch)
        }
        assertEquals(2, source.observationCount)
    }

    @Test
    fun `capability construction and retained authority stay closed`() {
        assertTrue(VfsPassiveReadCapability::class.java.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
        assertFalse(VfsPassiveReadCapability::class.java.declaredMethods.any { method ->
            !method.isSynthetic &&
                (method.name == "copy" || method.name.startsWith("component"))
        })
        val publicMethods = VfsPassiveReadCapability::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
            .map { method -> method.name }
        assertEquals(2, publicMethods.size)
        assertTrue(publicMethods.any { method -> method.startsWith("getCanonicalRoot-") })
        assertTrue(publicMethods.contains("getAdmittedEpoch"))
        val retainedTypes = VfsPassiveReadCapability::class.java.declaredFields
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .map { field -> field.type }
            .toSet()
        assertEquals(setOf(String::class.java, ProjectReadEpoch::class.java), retainedTypes)
        assertFalse(retainedTypes.any { type ->
            Project::class.java.isAssignableFrom(type) ||
                Function::class.java.isAssignableFrom(type) ||
                type.name.contains("ProjectReadEpoch\$Source")
        })
    }

    private fun assertMappedFailure(
        observedFailure: ProjectReadEpochObservationFailure,
        expectedFailure: VfsPassiveReadAdmissionFailure,
    ) {
        val source = RecordingFreshnessEpochSource()
        val admittedProject = admittedFreshnessProject(source)
        val expectedEpoch = source.observeEpoch()
        source.observation = { Refinement.Rejected(observedFailure) }

        assertEquals(
            expectedFailure,
            rejectedFreshnessFailure(admittedProject.admitVfsPassiveRead(expectedEpoch)),
        )
        assertEquals(2, source.observationCount)
    }
}
