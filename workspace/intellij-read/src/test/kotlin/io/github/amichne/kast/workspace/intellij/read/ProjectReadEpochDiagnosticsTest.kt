package io.github.amichne.kast.workspace.intellij.read

import com.google.gson.JsonParser
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ProjectReadEpochDiagnosticsTest {
    @Test
    fun `production samples report changed signals once without altering epoch authority`() {
        val records = mutableListOf<String>()
        val host = IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val diagnostics = ProjectReadEpochDiagnostics(host, records::add)
        val platform = RecordingProjectReadEpochPlatform()
        val source =
            LiveProjectReadEpochSource(
                    platform,
                    ProjectReadEpochMetadataCounter(),
                    ProjectReadEpochMetadataCounter(),
                    RecordingProjectReadEpochExecution(),
                    observation = diagnostics::record,
                )
                .source
        val initial = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch
        assertEquals(
            ProjectReadEpochRelation.SAME,
            initial.relationTo(assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch),
        )
        platform.psi += 1
        val moved = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch
        assertEquals(ProjectReadEpochRelation.MOVED, initial.relationTo(moved))
        source.observe()

        assertEquals(2, records.size)
        val baseline = JsonParser.parseString(records[0]).asJsonObject
        assertEquals(setOf("host", "outcome", "event"), baseline.keySet())
        assertEquals(host.value.toString(), baseline["host"].asString)
        assertEquals("kast_project_read_epoch", baseline["event"].asString)
        assertEquals("BASELINE", baseline["outcome"].asJsonObject["type"].asString)
        val change = JsonParser.parseString(records[1]).asJsonObject["outcome"].asJsonObject
        assertEquals(setOf("type", "signals"), change.keySet())
        assertEquals("MOVED", change["type"].asString)
        assertEquals(listOf("PSI"), change["signals"].asJsonArray.map { it.asString })
        records.forEach { assertFalse(it.contains("/workspace")) }
    }

    @Test
    fun `failed sample retains last proven signals and emits a finite rejection without source data`() {
        val records = mutableListOf<String>()
        val diagnostics =
            ProjectReadEpochDiagnostics(
                IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                records::add,
            )
        val platform = RecordingProjectReadEpochPlatform()
        val source =
            LiveProjectReadEpochSource(
                    platform,
                    ProjectReadEpochMetadataCounter(),
                    ProjectReadEpochMetadataCounter(),
                    RecordingProjectReadEpochExecution(),
                    observation = diagnostics::record,
                )
                .source
        source.observe()
        platform.throwAt = ProjectReadEpochObservationStage.ROOT_MODEL
        repeat(2) {
            assertEquals(
                ProjectReadEpochObservationFailure.ObservationFailed(ProjectReadEpochObservationStage.ROOT_MODEL),
                assertInstanceOf<ProjectReadEpochObservation.Rejected>(source.observe()).failure,
            )
        }
        platform.throwAt = null
        platform.psi += 1
        source.observe()
        assertEquals(3, records.size)
        val failure = JsonParser.parseString(records[1]).asJsonObject["outcome"].asJsonObject
        assertEquals("REJECTED", failure["type"].asString)
        assertEquals("OBSERVATION_FAILED", failure["failure"].asJsonObject["type"].asString)
        assertEquals("ROOT_MODEL", failure["failure"].asJsonObject["stage"].asString)
        val recovered = JsonParser.parseString(records[2]).asJsonObject["outcome"].asJsonObject
        assertEquals("MOVED", recovered["type"].asString)
        assertEquals(listOf("PSI"), recovered["signals"].asJsonArray.map { it.asString })
        records.forEach { assertFalse(it.contains("fixture ROOT_MODEL failure")) }
    }

    @Test
    fun `indexing rejection and stable recovery are distinct from observed movement`() {
        val records = mutableListOf<String>()
        val diagnostics =
            ProjectReadEpochDiagnostics(
                IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                records::add,
            )
        val platform = RecordingProjectReadEpochPlatform()
        val execution = RecordingProjectReadEpochExecution()
        val source =
            LiveProjectReadEpochSource(
                    platform,
                    ProjectReadEpochMetadataCounter(),
                    ProjectReadEpochMetadataCounter(),
                    execution,
                    observation = diagnostics::record,
                )
                .source
        source.observe()
        platform.dumbStates = ArrayDeque(listOf(true))
        assertEquals(
            ProjectReadEpochObservationFailure.DumbMode,
            assertInstanceOf<ProjectReadEpochObservation.Rejected>(source.observe()).failure,
        )
        source.observe()
        execution.dispatchThread = true
        execution.writeAccess = true
        platform.psi += 1
        source.observeBeforeWrite()
        val outcomes = records.map { JsonParser.parseString(it).asJsonObject["outcome"].asJsonObject }
        assertEquals(listOf("BASELINE", "REJECTED", "RECOVERED", "MOVED"), outcomes.map { it["type"].asString })
        assertEquals(setOf("type"), outcomes[2].keySet())
        assertEquals("UNAVAILABLE", outcomes[1]["failure"].asJsonObject["type"].asString)
        assertEquals("DUMB_MODE", outcomes[1]["failure"].asJsonObject["cause"].asString)
        assertEquals(listOf("PSI"), outcomes[3]["signals"].asJsonArray.map { it.asString })
    }

    @Test
    fun `every unavailable failure preserves its distinct encoded cause`() {
        val cases =
            listOf(
                ProjectReadEpochObservationFailure.WrongThread to "WRONG_THREAD",
                ProjectReadEpochObservationFailure.ProjectDisposed to "PROJECT_DISPOSED",
                ProjectReadEpochObservationFailure.ProjectNotOpen to "PROJECT_NOT_OPEN",
                ProjectReadEpochObservationFailure.ProjectNotInitialized to "PROJECT_NOT_INITIALIZED",
                ProjectReadEpochObservationFailure.ProjectRootUnavailable to "PROJECT_ROOT_UNAVAILABLE",
                ProjectReadEpochObservationFailure.ProjectRootMalformed to "PROJECT_ROOT_MALFORMED",
                ProjectReadEpochObservationFailure.DumbMode to "DUMB_MODE",
                ProjectReadEpochObservationFailure.GradleModelUnavailable to "GRADLE_MODEL_UNAVAILABLE",
                ProjectReadEpochObservationFailure.GradleModelIncomplete to "GRADLE_MODEL_INCOMPLETE",
                ProjectReadEpochObservationFailure.GradleModelAmbiguous to "GRADLE_MODEL_AMBIGUOUS",
                ProjectReadEpochObservationFailure.GradleRootUnavailable to "GRADLE_ROOT_UNAVAILABLE",
                ProjectReadEpochObservationFailure.GradleRootMalformed to "GRADLE_ROOT_MALFORMED",
                ProjectReadEpochObservationFailure.ImportTimestampsIncoherent to "IMPORT_TIMESTAMPS_INCOHERENT",
                ProjectReadEpochObservationFailure.VfsBatchLimitExceeded to "VFS_BATCH_LIMIT_EXCEEDED",
                ProjectReadEpochObservationFailure.VfsPathMalformed to "VFS_PATH_MALFORMED",
                ProjectReadEpochObservationFailure.SignalExhausted to "SIGNAL_EXHAUSTED",
                ProjectReadEpochObservationFailure.ReadPreempted to "READ_PREEMPTED",
            )
        cases.forEach { (failure, expected) ->
            val records = mutableListOf<String>()
            val diagnostics =
                ProjectReadEpochDiagnostics(
                    IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                    records::add,
                )
            diagnostics.record(Refinement.Rejected(failure))
            val outcome = JsonParser.parseString(records.single()).asJsonObject["outcome"].asJsonObject
            assertEquals(setOf("type", "failure"), outcome.keySet())
            assertEquals("REJECTED", outcome["type"].asString)
            assertEquals(setOf("type", "cause"), outcome["failure"].asJsonObject.keySet())
            assertEquals(expected, outcome["failure"].asJsonObject["cause"].asString)
        }
    }

    @Test
    fun `each retained equality input has a distinct finite diagnostic cause`() {
        val stable =
            ProjectReadEpochBoundary(
                signal(1),
                fixtureProjectEpochRoot("/workspace/kast"),
                fixtureGradleEpochRoot("/workspace/kast"),
                10,
                10,
                signal(1),
                signal(1),
                signal(1),
                signal(1),
                false,
            )
        val before = stable.admit()
        val cases =
            listOf(
                stable.copy(projectModelRevision = signal(2)) to ProjectReadEpochSignal.WORKSPACE_MODEL,
                stable.copy(projectRoot = fixtureProjectEpochRoot("/workspace/other")) to
                    ProjectReadEpochSignal.PROJECT_ROOT,
                stable.copy(gradleRoot = fixtureGradleEpochRoot("/workspace/other")) to
                    ProjectReadEpochSignal.GRADLE_ROOT,
                stable.copy(lastImportTimestamp = 11) to ProjectReadEpochSignal.IMPORT_STATE,
                stable.copy(psiModificationCount = signal(2)) to ProjectReadEpochSignal.PSI,
                stable.copy(rootFilteredVfsBatchCount = signal(2)) to ProjectReadEpochSignal.VFS,
                stable.copy(rootModelModificationCount = signal(2)) to ProjectReadEpochSignal.ROOT_MODEL,
                stable.copy(dumbModeModificationCount = signal(2)) to ProjectReadEpochSignal.INDEXING,
            )
        assertEquals(emptySet<ProjectReadEpochSignal>(), before.changedSignalsFrom(stable.admit()))
        cases.forEach { (boundary, expected) ->
            assertEquals(setOf(expected), boundary.admit().changedSignalsFrom(before))
        }
    }

    private fun signal(value: Long) = ProjectReadEpochSignalSample.Value(value)

    private fun ProjectReadEpochBoundary.admit() =
        assertInstanceOf<Refinement.Refined<ProjectReadEpochState>>(ProjectReadEpochState.admit(this)).value
}
