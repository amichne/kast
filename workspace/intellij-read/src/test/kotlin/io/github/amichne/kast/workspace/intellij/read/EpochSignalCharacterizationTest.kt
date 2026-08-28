package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class EpochSignalCharacterizationTest {
    @Test
    fun `two samples detect every supported stable and moving fixture`() {
        val observed = EpochCaseId.entries.map(::characterizeEpochCase)

        assertEquals(EpochSignalLedgerContract.document.cases, observed)
        assertTrue(observed.all { case -> case.sampleCount == 2 })
        assertEquals(22, observed.sumOf(EpochCaseDocument::sampleCount))
        assertEquals(
            1_000,
            observed.single { case -> case.caseId == EpochCaseId.VFS_EVENT_STORM }
                .vfsEventCount,
        )
        assertEquals(
            listOf(EpochProjectModelTransition.GRADLE_IMPORT_COMPLETED),
            observed.single { case -> case.caseId == EpochCaseId.GRADLE_IMPORT_COMPLETED }
                .projectModelTransitions,
        )
        assertEquals(
            listOf(EpochProjectModelTransition.GRADLE_ROOT_CHANGED),
            observed.single { case -> case.caseId == EpochCaseId.GRADLE_ROOT_MOVEMENT }
                .projectModelTransitions,
        )
        assertEquals(
            listOf(EpochDumbModeState.SMART, EpochDumbModeState.SMART),
            observed.single { case -> case.caseId == EpochCaseId.SMART_DUMB_SMART }
                .dumbModeSamples,
        )
        assertEquals(
            listOf(
                EpochDumbModeTransition.SMART_TO_DUMB,
                EpochDumbModeTransition.DUMB_TO_SMART,
            ),
            observed.single { case -> case.caseId == EpochCaseId.SMART_DUMB_SMART }
                .dumbModeTransitions,
        )
    }

    @Test
    fun `compile-only contract references the exact public IDEA 262 signal APIs`() {
        assertEquals(emptyList<EpochClassContractFailure>(), EpochSignalClassContract.verify())
        assertEquals(
            listOf(
                "VirtualFileManager.modificationCount",
                "VirtualFileManager.structureModificationCount",
            ),
            EpochSignalLedgerContract.document.rejectedConstantZeroAuthorities,
        )
    }

    @Test
    fun `root-filtered VFS events detect exact inbound outbound and storm movement`() {
        assertEquals(
            0,
            rootFilteredBatchCount(
                listOf(EpochVfsObservedEvent.Change(Path.of("/workspace/kast-other/A.kt"))),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                listOf(EpochVfsObservedEvent.Change(Path.of("/workspace/kast/src/A.kt"))),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                listOf(
                    EpochVfsObservedEvent.Move(
                        Path.of("/workspace/kast/src/A.kt"),
                        Path.of("/workspace/other/A.kt"),
                    ),
                ),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                listOf(
                    EpochVfsObservedEvent.Move(
                        Path.of("/workspace/other/A.kt"),
                        Path.of("/workspace/kast/src/A.kt"),
                    ),
                ),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                listOf(
                    EpochVfsObservedEvent.Rename(
                        Path.of("/workspace/kast/src/A.kt"),
                        Path.of("/workspace/other/A.kt"),
                    ),
                ),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                listOf(
                    EpochVfsObservedEvent.Rename(
                        Path.of("/workspace/other/A.kt"),
                        Path.of("/workspace/kast/src/A.kt"),
                    ),
                ),
            ),
        )
        assertEquals(
            1,
            rootFilteredBatchCount(
                List(1_000) { index ->
                    EpochVfsObservedEvent.Change(
                        Path.of("/workspace/kast/src/Event$index.kt"),
                    )
                },
            ),
        )
    }

    @Test
    fun `generated epoch ledger decodes and has the exact canonical bytes`() {
        val report = System.getProperty("kast.ide.epoch.ledger.report")
            ?.let(Path::of)
            ?: fail("missing generated KVP-015 epoch-ledger report path")
        val raw = Files.readString(report)

        val admission = EpochSignalLedgerContract.admit(raw)

        assertEquals(
            EpochLedgerAdmission.Admitted(EpochSignalLedgerContract.document, 22),
            admission,
        )
        assertEquals(EpochSignalLedgerContract.canonicalBytes, raw)
    }

    private fun rootFilteredBatchCount(events: List<EpochVfsObservedEvent>): Long {
        val counter = EpochVfsMetadataCounter(EpochFixtureRoot.KAST)
        counter.recordEvents(events)
        return counter.value
    }
}

class IdeProjectReadEpochTest {
    @Test
    fun `production observer uses only the exact IDEA 262 metadata surface`() {
        assertEquals(emptyList<EpochClassContractFailure>(), EpochSignalClassContract.verifyProductionEpoch())
    }

    @Test
    fun `live source maps lifecycle dumb root Gradle stage and preemption failures`() {
        fun failure(
            configure: (RecordingProjectReadEpochPlatform) -> Unit,
            expected: ProjectReadEpochObservationFailure,
        ) = configure to expected
        val cases = listOf(
            failure({ it.disposed = true }, ProjectReadEpochObservationFailure.ProjectDisposed),
            failure({ it.open = false }, ProjectReadEpochObservationFailure.ProjectNotOpen),
            failure({ it.initialized = false },
                ProjectReadEpochObservationFailure.ProjectNotInitialized),
            failure({ it.dumbStates = ArrayDeque(listOf(true)) },
                ProjectReadEpochObservationFailure.DumbMode,
            ),
            failure({ it.projectRoot = null },
                ProjectReadEpochObservationFailure.ProjectRootUnavailable),
            failure({ it.gradle = Refinement.Rejected(
                ProjectReadEpochObservationFailure.GradleModelUnavailable,
            ) }, ProjectReadEpochObservationFailure.GradleModelUnavailable),
            failure({ it.throwAt = ProjectReadEpochObservationStage.ROOT_MODEL },
                ProjectReadEpochObservationFailure.ObservationFailed(
                    ProjectReadEpochObservationStage.ROOT_MODEL,
                ),
            ),
            failure({ it.dumbStates = ArrayDeque(listOf(false, true)) },
                ProjectReadEpochObservationFailure.DumbMode,
            ),
        )
        cases.forEach { (configure, expected) ->
            val platform = RecordingProjectReadEpochPlatform().also(configure)
            assertEquals(expected, assertRejected(liveObservation(platform)).failure)
        }
        assertEquals(
            ProjectReadEpochObservationFailure.WrongThread,
            assertRejected(liveObservation(
                execution = RecordingProjectReadEpochExecution(true),
            )).failure,
        )
        assertEquals(
            ProjectReadEpochObservationFailure.ObservationFailed(
                ProjectReadEpochObservationStage.THREAD,
            ),
            assertRejected(liveObservation(
                execution = RecordingProjectReadEpochExecution(
                    probeFailure = IllegalStateException("thread probe failed"),
                ),
            )).failure,
        )
        assertEquals(
            ProjectReadEpochObservationFailure.ReadPreempted,
            assertRejected(liveObservation(
                execution = RecordingProjectReadEpochExecution(
                    failure = ReadAction.CannotReadException(),
                ),
            )).failure,
        )
        assertThrows(ProcessCanceledException::class.java) {
            liveObservation(platform = RecordingProjectReadEpochPlatform().apply {
                cancellation = ProcessCanceledException()
            })
        }
    }

    @Test
    fun `all characterized signals move one retained project runtime epoch`() {
        val source = AdapterEpochSource(stableProjectReadEpochBoundary())
        val before = source.observeEpoch()

        assertEquals(ProjectReadEpochRelation.SAME, before.relationTo(source.observeEpoch()))

        val stable = stableProjectReadEpochBoundary()
        listOf(
            stable.copy(projectModelRevision = signal(2)),
            stable.copy(projectRoot = fixtureProjectEpochRoot("/workspace/kast-moved")),
            stable.copy(gradleRoot = fixtureGradleEpochRoot("/workspace/kast-moved")),
            stable.copy(lastImportTimestamp = 11),
            stable.copy(lastImportTimestamp = 11, lastSuccessfulImportTimestamp = 11),
            stable.copy(psiModificationCount = signal(2)),
            stable.copy(rootFilteredVfsBatchCount = signal(2)),
            stable.copy(rootModelModificationCount = signal(2)),
            stable.copy(dumbModeModificationCount = signal(3)),
        ).forEach { moved ->
            source.boundary = moved
            assertEquals(ProjectReadEpochRelation.MOVED, before.relationTo(source.observeEpoch()))
        }
    }

    @Test
    fun `equal snapshots from distinct admitted sources are incomparable`() {
        val boundary = stableProjectReadEpochBoundary()
        val first = AdapterEpochSource(boundary).observeEpoch()
        val second = AdapterEpochSource(boundary).observeEpoch()

        assertEquals(ProjectReadEpochRelation.INCOMPARABLE, first.relationTo(second))
    }

    @Test
    fun `dumb malformed unavailable incoherent and exhausted observations reject exactly`() {
        val stable = stableProjectReadEpochBoundary()
        val cases = listOf(
            stable.copy(dumb = true) to ProjectReadEpochObservationFailure.DumbMode,
            stable.copy(lastImportTimestamp = 9, lastSuccessfulImportTimestamp = 10) to
                ProjectReadEpochObservationFailure.ImportTimestampsIncoherent,
            stable.copy(psiModificationCount = signal(-1)) to
                ProjectReadEpochObservationFailure.SignalExhausted,
            stable.copy(
                rootFilteredVfsBatchCount = ProjectReadEpochSignalSample.Rejected(
                    ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
                ),
            ) to ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
        )

        cases.forEach { (boundary, expected) ->
            val rejected = assertRejected(AdapterEpochSource(boundary).observe())
            assertEquals(expected, rejected.failure)
        }
    }

    @Test
    fun `root-filtered production counter advances once per bounded VFS batch`() {
        assertEquals(signal(0), observeVfsBatch(
            listOf(ProjectReadEpochVfsEvent.Change("/workspace/kast-other/A.kt")),
        ))
        assertEquals(signal(1), observeVfsBatch(
            listOf(
                ProjectReadEpochVfsEvent.Move(
                    "/workspace/other/A.kt",
                    "/workspace/kast/src/A.kt",
                ),
            ),
        ))
        assertEquals(signal(1), observeVfsBatch(
            listOf(
                ProjectReadEpochVfsEvent.Rename(
                    "/workspace/kast/src/A.kt",
                    "/workspace/other/A.kt",
                ),
            ),
        ))
        assertEquals(signal(1), observeVfsBatch(
            List(1_000) { index ->
                ProjectReadEpochVfsEvent.Change("/workspace/kast/src/Event$index.kt")
            },
        ))
        assertEquals(
            ProjectReadEpochSignalSample.Rejected(
                ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
            ),
            observeVfsBatch(
                List(PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH + 1) {
                    ProjectReadEpochVfsEvent.Change("/workspace/kast/src/A.kt")
                },
            ),
        )
        listOf("relative/A.kt", "/" + "a".repeat(4_096), "/" + "€".repeat(2_731)).forEach { raw ->
            assertEquals(
                ProjectReadEpochSignalSample.Rejected(
                    ProjectReadEpochObservationFailure.VfsPathMalformed,
                ),
                observeVfsBatch(listOf(ProjectReadEpochVfsEvent.Change(raw))),
            )
        }
    }

    @Test
    fun `admitted project exposes only the typed epoch observation`() {
        val method = AdmittedIdeProject::class.java.methods.single { candidate ->
            candidate.name == "observeReadEpoch"
        }

        assertEquals(ProjectReadEpochObservation::class.java, method.returnType)
        assertTrue(
            AdmittedIdeProject::class.java.methods.none { candidate ->
                candidate.returnType.name.contains("ProjectReadEpoch\$Source")
            },
        )
    }

    private fun observeVfsBatch(
        events: List<ProjectReadEpochVfsEvent>,
    ): ProjectReadEpochSignalSample {
        val counter = ProjectReadEpochMetadataCounter()
        val root = ProjectReadEpochVfsRoot.from(FIXTURE_ROOT)
        when (val result = observeProjectReadEpochVfsBatch(root, events)) {
            ProjectReadEpochVfsBatchObservation.OutsideRoot -> Unit
            ProjectReadEpochVfsBatchObservation.TouchesRoot -> counter.advance()
            is ProjectReadEpochVfsBatchObservation.Rejected -> counter.reject(result.failure)
        }
        return counter.sample()
    }

    private fun liveObservation(
        platform: RecordingProjectReadEpochPlatform = RecordingProjectReadEpochPlatform(),
        execution: RecordingProjectReadEpochExecution = RecordingProjectReadEpochExecution(),
    ): ProjectReadEpochObservation = LiveProjectReadEpochSource(
        platform,
        ProjectReadEpochMetadataCounter(),
        ProjectReadEpochMetadataCounter(),
        execution,
    ).source.observe()
}

private class AdapterEpochSource(
    var boundary: ProjectReadEpochBoundary,
) {
    private val source = ProjectReadEpoch.Source.create { ProjectReadEpochState.admit(boundary) }
    fun observe(): ProjectReadEpochObservation = source.observe()

    fun observeEpoch(): ProjectReadEpoch<*> = when (val observed = source.observe()) {
        is ProjectReadEpochObservation.Observed -> observed.epoch
        is ProjectReadEpochObservation.Rejected -> fail("unexpected ${observed.failure}")
    }
}

private fun stableProjectReadEpochBoundary() = ProjectReadEpochBoundary(
    projectModelRevision = signal(1),
    projectRoot = fixtureProjectEpochRoot("/workspace/kast"),
    gradleRoot = fixtureGradleEpochRoot("/workspace/kast"),
    lastImportTimestamp = 10,
    lastSuccessfulImportTimestamp = 10,
    psiModificationCount = signal(1),
    rootFilteredVfsBatchCount = signal(1),
    rootModelModificationCount = signal(1),
    dumbModeModificationCount = signal(1),
    dumb = false,
)

private fun signal(value: Long) = ProjectReadEpochSignalSample.Value(value)

private fun assertRejected(
    observation: ProjectReadEpochObservation,
): ProjectReadEpochObservation.Rejected = observation as? ProjectReadEpochObservation.Rejected
    ?: fail("expected rejected observation, got ${observation::class.simpleName}")
