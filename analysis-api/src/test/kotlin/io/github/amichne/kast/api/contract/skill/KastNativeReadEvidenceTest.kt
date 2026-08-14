package io.github.amichne.kast.api.contract.skill

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KastNativeReadEvidenceTest {
    @Test
    fun `stage evidence requires complete non-negative observations`() {
        assertThrows(IllegalArgumentException::class.java) {
            KastNativeReadStages(emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            KastReadStageObservation.Measured(-1L)
        }
    }

    @Test
    fun `native evidence keeps exactness and qualifications consistent`() {
        val stages = KastNativeReadStages(
            KastNativeReadStage.entries.associateWith {
                KastReadStageObservation.NotApplicable
            },
        )
        val work = KastNativeReadWork(
            vfsRefreshCount = 0L,
            gradleImportCount = 0L,
            graphBuildCount = 0L,
            sqliteWriteCount = 0L,
            readActionCount = 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            KastReadEvidence.NativeIntellij(
                generation = 1L,
                completeness = KastNativeReadCompleteness.QUALIFIED,
                qualifications = emptySet(),
                stages = stages,
                work = work,
                projectionBytes = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KastNativeReadWork(
                vfsRefreshCount = 0L,
                gradleImportCount = 0L,
                graphBuildCount = 0L,
                sqliteWriteCount = -1L,
                readActionCount = 1L,
            )
        }
    }
}
