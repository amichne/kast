package io.github.amichne.kast.workspace.intellij.read

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
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
