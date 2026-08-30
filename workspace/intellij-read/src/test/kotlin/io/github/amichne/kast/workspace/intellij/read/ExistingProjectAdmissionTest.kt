package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
        var epochSourceInstallations = 0
        val epochSourceFactory = ExistingProjectReadEpochSourceFactory { project, root ->
            epochSourceInstallations += 1
            FIXTURE_EPOCH_SOURCE_FACTORY.create(project, root)
        }

        val admitted = when (
            val result = AdmittedIdeProject.admitObserved(
                opaqueProject(),
                FIXTURE_ROOT,
                FIXTURE_COMPATIBILITY,
                FIXTURE_COMPATIBILITY_POLICY,
                observation,
                epochSourceFactory,
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
        assertEquals(1, epochSourceInstallations)
    }

    @Test
    fun `validation observes the exact policy without an epoch source factory`() {
        val observation = RecordingProjectObservation()

        val validation = ExistingProjectValidation.validateObserved(
            opaqueProject(),
            FIXTURE_ROOT,
            FIXTURE_COMPATIBILITY,
            FIXTURE_COMPATIBILITY_POLICY,
            observation,
        )

        assertEquals(ExistingProjectValidation.Validated, validation)
        assertEquals(ExistingProjectObservationStage.entries, observation.observedStages)
    }

    @Test
    fun `project service session retains one epoch authority across repeated admission`() {
        val project = opaqueProject()
        val session = AdmittedIdeProjectSession()
        var admissionAttempts = 0
        var epochSourceInstallations = 0
        val admissions = ExistingProjectAdmissionOperations {
                candidateProject, expectedRoot, compatibilityCandidate, compatibilityPolicy ->
            admissionAttempts += 1
            AdmittedIdeProject.admitObserved(
                candidateProject,
                expectedRoot,
                compatibilityCandidate,
                compatibilityPolicy,
                RecordingProjectObservation(),
                ExistingProjectReadEpochSourceFactory { sourceProject, sourceRoot ->
                    epochSourceInstallations += 1
                    FIXTURE_EPOCH_SOURCE_FACTORY.create(sourceProject, sourceRoot)
                },
            )
        }

        val first = session.admitUsing(
            project,
            FIXTURE_ROOT,
            FIXTURE_COMPATIBILITY,
            FIXTURE_COMPATIBILITY_POLICY,
            admissions,
        ) as ExistingProjectAdmission.Admitted
        val repeated = session.admitUsing(
            project,
            FIXTURE_ROOT,
            FIXTURE_COMPATIBILITY,
            FIXTURE_COMPATIBILITY_POLICY,
            admissions,
        ) as ExistingProjectAdmission.Admitted
        val mismatched = session.admitUsing(
            project,
            OTHER_FIXTURE_ROOT,
            FIXTURE_COMPATIBILITY,
            FIXTURE_COMPATIBILITY_POLICY,
            admissions,
        )

        assertSame(first.project, repeated.project)
        assertEquals(1, admissionAttempts)
        assertEquals(1, epochSourceInstallations)
        assertEquals(
            ExistingProjectAdmissionFailure.RetainedAuthorityMismatch,
            admittedFailure(mismatched),
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

}
