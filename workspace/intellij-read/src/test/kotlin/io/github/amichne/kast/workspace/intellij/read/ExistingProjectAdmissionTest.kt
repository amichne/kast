package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ExistingProjectAdmissionTest {
    @Test
    fun `epoch source installation closes a disposal race`() {
        assertEquals(
            Refinement.Rejected(
                ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed,
            ),
            LiveProjectReadEpochSourceFactory.create(disposedProject(), FIXTURE_ROOT),
        )
    }

    @Test
    fun `only the exact ready existing Project yields the stronger authority`() {
        val observation = RecordingProjectObservation()

        val admitted = when (
            val result = AdmittedIdeProject.admitObserved(
                opaqueProject(),
                FIXTURE_ROOT,
                FIXTURE_COMPATIBILITY,
                FIXTURE_COMPATIBILITY_POLICY,
                observation,
                FIXTURE_EPOCH_SOURCE_FACTORY,
            )
        ) {
            is ExistingProjectAdmission.Admitted -> result.project
            is ExistingProjectAdmission.Rejected -> fail("exact fixture rejected: ${result.failure}")
        }

        assertEquals(FIXTURE_ROOT, admitted.canonicalRoot)
        assertEquals(FIXTURE_COMPATIBILITY.ideBuild, admitted.compatibility.ideBuild.value)
        assertEquals(
            FIXTURE_COMPATIBILITY.kotlinPluginBuild,
            admitted.compatibility.kotlinPluginBuild.value,
        )
        assertEquals(
            ExistingProjectObservationStage.entries,
            observation.observedStages,
        )
    }

    @Test
    fun `live root observation returns the supplied proof only for the exact path`() {
        assertEquals(
            ExistingProjectPathMatch.EXACT,
            observeCanonicalPath(FIXTURE_ROOT.value, FIXTURE_ROOT),
        )
        assertEquals(
            ExistingProjectRootObservation.Available(FIXTURE_ROOT),
            LiveExistingProjectObservation.root(
                projectWithBasePath(FIXTURE_ROOT.value),
                FIXTURE_ROOT,
            ),
        )
    }

    @Test
    fun `one exact ready current cached Gradle model is complete`() {
        assertEquals(ExistingProjectImportState.CURRENT, observeImportState(5, 5))
        assertEquals(
            ExistingProjectGradleModelState.COMPLETE,
            classifyCachedGradleModel(
                listOf(
                    ExistingProjectGradleModelObservation(
                        ExistingProjectPathMatch.EXACT,
                        ExistingProjectStructureState.READY,
                        ExistingProjectImportState.CURRENT,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the admitted value exposes no public live Project member`() {
        val exposedTypes = buildList {
            AdmittedIdeProject::class.java.declaredMethods
                .filter { method ->
                    Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers)
                }
                .forEach { method ->
                    add(method.returnType)
                    addAll(method.parameterTypes)
                }
            AdmittedIdeProject::class.java.constructors.forEach { constructor ->
                addAll(constructor.parameterTypes)
            }
            AdmittedIdeProject::class.java.fields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .forEach { field -> add(field.type) }
        }

        assertFalse(
            exposedTypes.any { exposed -> Project::class.java.isAssignableFrom(exposed) },
            "AdmittedIdeProject exposed a public live Project member: $exposedTypes",
        )
    }

    @Test
    fun `generated project admission report is the exact canonical document`() {
        val reportPath = System.getProperty("kast.existing.project.admission.report")
            ?.let(Path::of)
            ?: fail("missing generated KVP-014 report path")

        assertEquals(EXPECTED_PROJECT_ADMISSION_REPORT, Files.readString(reportPath))
    }
}
