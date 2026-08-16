package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationDurabilityFailure
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IntellijAddFileWriteProtocolTest {
    private val input = IntellijAddFileInput(
        "/workspace/app/src/main/kotlin/sample/Added.kt",
        "package sample\n\nclass Added\n",
    )

    @Test
    fun `absent file is staged before durability and created only during save`() {
        val session = FakeAddFileSession()

        val result = IntellijAddFileWriteProtocol().execute(
            input,
            MutationDurabilityBarrier {
                session.events += "durable"
                assertEquals(IntellijAddFilePhysicalState.Absent, session.physicalState())
                MutationDurabilityResult.Durable
            },
            session,
        )

        assertInstanceOf(IntellijWriteProtocolResult.Applied::class.java, result)
        assertEquals(listOf("stage", "durable", "save", "observe"), session.events)
        assertEquals(IntellijAddFilePhysicalState.Present(input.postimageText), session.physicalState())
    }

    @Test
    fun `durability rejection clears staged file without creating a physical target`() {
        val session = FakeAddFileSession()

        val result = IntellijAddFileWriteProtocol().execute(
            input,
            MutationDurabilityBarrier {
                MutationDurabilityResult.Rejected(
                    MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED,
                )
            },
            session,
        )

        assertInstanceOf(IntellijWriteProtocolResult.RejectedAfterRollback::class.java, result)
        assertEquals(listOf("stage", "clear"), session.events)
        assertEquals(IntellijAddFilePhysicalState.Absent, session.physicalState())
    }

    @Test
    fun `file appearing before staging is rejected as stale`() {
        val session = FakeAddFileSession(physical = "package sample\nclass Other\n")

        val result = IntellijAddFileWriteProtocol().execute(
            input,
            MutationDurabilityBarrier { MutationDurabilityResult.Durable },
            session,
        )

        assertInstanceOf(IntellijWriteProtocolResult.RejectedBeforeMutation::class.java, result)
        assertEquals(emptyList<String>(), session.events)
    }
}

private class FakeAddFileSession(
    private var physical: String? = null,
) : IntellijAddFileStagingSession {
    val events = mutableListOf<String>()

    override fun physicalState(): IntellijAddFilePhysicalState = physical?.let(
        IntellijAddFilePhysicalState::Present,
    ) ?: IntellijAddFilePhysicalState.Absent

    override fun stage(postimageText: String): IntellijAddFileStageResult {
        events += "stage"
        return IntellijAddFileStageResult.Staged(IntellijStagedAddFile(postimageText))
    }

    override fun clearStage(staged: IntellijStagedAddFile): IntellijSessionStepResult {
        events += "clear"
        return IntellijSessionStepResult.Completed
    }

    override fun save(staged: IntellijStagedAddFile): IntellijSessionStepResult {
        events += "save"
        physical = staged.postimageText
        return IntellijSessionStepResult.Completed
    }

    override fun observe(): IntellijPhysicalSourceObservation {
        events += "observe"
        return IntellijPhysicalSourceObservation.Observed(
            checkNotNull(physical).toByteArray(),
            setOf("/workspace/app/src/main/kotlin/sample/Added.kt"),
        )
    }
}
