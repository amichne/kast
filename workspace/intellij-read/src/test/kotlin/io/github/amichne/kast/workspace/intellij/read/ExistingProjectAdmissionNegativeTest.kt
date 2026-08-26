package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityIdentityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExistingProjectAdmissionNegativeTest {
    @Test
    fun `every finite rejection stops before the next observation stage`() {
        rejectionCases().forEach { case ->
            val observation = RecordingProjectObservation().also(case.configure)
            val failure = admittedFailure(
                admit(observation),
            )

            assertEquals(case.failure, failure, case.name)
            assertEquals(
                ExistingProjectObservationStage.entries.take(case.observedStageCount),
                observation.observedStages,
                case.name,
            )
        }
    }

    @Test
    fun `an exception at each observation stage is closed and short circuits`() {
        ExistingProjectObservationStage.entries.forEachIndexed { index, stage ->
            val observation = RecordingProjectObservation(throwAt = stage)

            val failure = admittedFailure(admit(observation))

            assertEquals(
                ExistingProjectAdmissionFailure.ObservationFailed(stage),
                failure,
                stage.name,
            )
            assertEquals(
                ExistingProjectObservationStage.entries.take(index + 1),
                observation.observedStages,
                stage.name,
            )
        }
    }

    @Test
    fun `platform cancellation propagates from every observation stage`() {
        ExistingProjectObservationStage.entries.forEachIndexed { index, stage ->
            val cancellation = ProcessCanceledException()
            val observation = RecordingProjectObservation(
                throwAt = stage,
                thrownFailure = cancellation,
            )

            val propagated = assertThrows(ProcessCanceledException::class.java) {
                admit(observation)
            }

            assertSame(cancellation, propagated, stage.name)
            assertEquals(
                ExistingProjectObservationStage.entries.take(index + 1),
                observation.observedStages,
                stage.name,
            )
        }
    }

    @Test
    fun `live root observation rejects relative non-normalized and mismatched paths`() {
        listOf("workspace/kast", "/workspace/./kast").forEach { raw ->
            assertEquals(
                ExistingProjectRootObservation.Unavailable,
                LiveExistingProjectObservation.root(projectWithBasePath(raw), FIXTURE_ROOT),
                raw,
            )
        }
        assertEquals(
            ExistingProjectRootObservation.Mismatch,
            LiveExistingProjectObservation.root(
                projectWithBasePath("/workspace/other"),
                FIXTURE_ROOT,
            ),
        )
    }

    @Test
    fun `platform path comparison rejects relative non-normalized and mismatched paths`() {
        listOf(null, "workspace/kast", "/workspace/./kast").forEach { raw ->
            assertEquals(
                ExistingProjectPathMatch.UNAVAILABLE,
                observeCanonicalPath(raw, FIXTURE_ROOT),
                raw,
            )
        }
        assertEquals(
            ExistingProjectPathMatch.MISMATCH,
            observeCanonicalPath("/workspace/other", FIXTURE_ROOT),
        )
    }

    @Test
    fun `cached Gradle model classifier rejects every incomplete shape`() {
        assertEquals(
            ExistingProjectGradleModelState.UNAVAILABLE,
            classifyCachedGradleModel(emptyList()),
        )
        assertEquals(
            ExistingProjectGradleModelState.UNAVAILABLE,
            classifyCachedGradleModel(
                listOf(gradleObservation(path = ExistingProjectPathMatch.MISMATCH)),
            ),
        )
        listOf(
            gradleObservation(structure = ExistingProjectStructureState.INCOMPLETE),
            gradleObservation(importState = ExistingProjectImportState.ABSENT),
            gradleObservation(importState = ExistingProjectImportState.STALE),
        ).forEach { observation ->
            assertEquals(
                ExistingProjectGradleModelState.INCOMPLETE,
                classifyCachedGradleModel(listOf(observation)),
            )
        }
        assertEquals(
            ExistingProjectGradleModelState.INCOMPLETE,
            classifyCachedGradleModel(listOf(gradleObservation(), gradleObservation())),
        )
        assertEquals(ExistingProjectImportState.ABSENT, observeImportState(0, 0))
        assertEquals(ExistingProjectImportState.STALE, observeImportState(4, 5))
    }

    private fun admit(
        observation: RecordingProjectObservation,
    ): ExistingProjectAdmission = AdmittedIdeProject.admitObserved(
        opaqueProject(),
        FIXTURE_ROOT,
        FIXTURE_COMPATIBILITY,
        FIXTURE_COMPATIBILITY_POLICY,
        observation,
        FIXTURE_EPOCH_SOURCE_FACTORY,
    )

    private data class RejectionCase(
        val name: String,
        val failure: ExistingProjectAdmissionFailure,
        val observedStageCount: Int,
        val configure: RecordingProjectObservation.() -> Unit,
    )

    private fun rejectionCases(): List<RejectionCase> = listOf(
        rejection(
            "disposed",
            ExistingProjectAdmissionFailure.ProjectDisposed,
            ExistingProjectObservationStage.DISPOSAL,
        ) { disposed = true },
        rejection(
            "not open",
            ExistingProjectAdmissionFailure.ProjectNotOpen,
            ExistingProjectObservationStage.OPEN,
        ) { open = false },
        rejection(
            "not initialized",
            ExistingProjectAdmissionFailure.ProjectNotInitialized,
            ExistingProjectObservationStage.INITIALIZATION,
        ) { initialized = false },
        rejection(
            "root unavailable",
            ExistingProjectAdmissionFailure.ProjectRootUnavailable,
            ExistingProjectObservationStage.ROOT,
        ) { projectRoot = ExistingProjectRootObservation.Unavailable },
        rejection(
            "wrong root",
            ExistingProjectAdmissionFailure.ProjectRootMismatch,
            ExistingProjectObservationStage.ROOT,
        ) { projectRoot = ExistingProjectRootObservation.Mismatch },
        rejection(
            "available other root",
            ExistingProjectAdmissionFailure.ProjectRootMismatch,
            ExistingProjectObservationStage.ROOT,
        ) { projectRoot = ExistingProjectRootObservation.Available(OTHER_FIXTURE_ROOT) },
        rejection(
            "model unavailable",
            ExistingProjectAdmissionFailure.GradleModelUnavailable,
            ExistingProjectObservationStage.GRADLE_MODEL,
        ) { gradleModelState = ExistingProjectGradleModelState.UNAVAILABLE },
        rejection(
            "model incomplete",
            ExistingProjectAdmissionFailure.GradleModelIncomplete,
            ExistingProjectObservationStage.GRADLE_MODEL,
        ) { gradleModelState = ExistingProjectGradleModelState.INCOMPLETE },
        rejection(
            "dumb mode",
            ExistingProjectAdmissionFailure.DumbMode,
            ExistingProjectObservationStage.INDEXING,
        ) { indexingState = ExistingProjectIndexingState.DUMB },
        rejection(
            "K2 unavailable",
            ExistingProjectAdmissionFailure.K2Unavailable,
            ExistingProjectObservationStage.KOTLIN_MODE,
        ) { kotlinModeState = ExistingProjectKotlinMode.K1 },
        rejection(
            "host identity unavailable",
            ExistingProjectAdmissionFailure.HostIdentityUnavailable,
            ExistingProjectObservationStage.HOST_IDENTITY,
        ) { hostIdentity = ExistingProjectHostIdentityObservation.Unavailable },
        rejection(
            "host identity malformed",
            ExistingProjectAdmissionFailure.HostIncompatible(
                IdeHostCompatibilityFailure.Malformed(
                    IdeHostCompatibilityField.IDE_BUILD,
                    IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT,
                ),
            ),
            ExistingProjectObservationStage.HOST_IDENTITY,
        ) {
            hostIdentity = ExistingProjectHostIdentityObservation.Rejected(
                IdeHostCompatibilityFailure.Malformed(
                    IdeHostCompatibilityField.IDE_BUILD,
                    IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT,
                ),
            )
        },
        RejectionCase(
            name = "incompatible host",
            failure = ExistingProjectAdmissionFailure.HostIncompatible(
                IdeHostCompatibilityFailure.Mismatch(
                    IdeHostCompatibilityIdentityField.IDE_BUILD,
                ),
            ),
            observedStageCount = ExistingProjectObservationStage.entries.size,
            configure = {
                hostIdentity = fixtureHostIdentity(ideBuild = "262.9437.186")
            },
        ),
    )

    private fun rejection(
        name: String,
        failure: ExistingProjectAdmissionFailure,
        stop: ExistingProjectObservationStage,
        configure: RecordingProjectObservation.() -> Unit,
    ): RejectionCase = RejectionCase(
        name = name,
        failure = failure,
        observedStageCount = stop.ordinal + 1,
        configure = configure,
    )

    private fun gradleObservation(
        path: ExistingProjectPathMatch = ExistingProjectPathMatch.EXACT,
        structure: ExistingProjectStructureState = ExistingProjectStructureState.READY,
        importState: ExistingProjectImportState = ExistingProjectImportState.CURRENT,
    ) = ExistingProjectGradleModelObservation(path, structure, importState)
}
