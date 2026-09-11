package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationDurabilityFailure
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import io.github.amichne.kast.change.apply.SourceWriteFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceWriteProtocolTest {
    @Test
    fun `existing rollback distinguishes exact preimage postimage and divergent bytes`() {
        val preimage = "before".toByteArray()
        val postimage = "after".toByteArray()

        assertEquals(
            ExistingRollbackPhysicalState.Preimage,
            existingRollbackPhysicalState(preimage.copyOf(), preimage, postimage),
        )
        assertEquals(
            ExistingRollbackPhysicalState.Postimage,
            existingRollbackPhysicalState(postimage.copyOf(), preimage, postimage),
        )
        assertEquals(
            ExistingRollbackPhysicalState.Diverged,
            existingRollbackPhysicalState("other".toByteArray(), preimage, postimage),
        )
    }

    private val input =
        IntellijMutationInput(
            sourcePath = "/workspace/app/src/main/kotlin/sample/Service.kt",
            preimageText = "fun service() = 0\n",
            postimageText = "fun service() = 0\n\nfun added() = 1\n",
            mutations = listOf(IntellijTextMutation(17, 17, "\nfun added() = 1")),
        )

    @Test
    fun `precondition rejection after write acquisition preserves a racing edit without rollback`() {
        val events = mutableListOf<String>()
        val userText = "fun service() = 42\n"
        val session = FakeDocumentSession(input.preimageText, events, rejectedMutationText = userText)
        val barrier = MutationDurabilityBarrier {
            events += "durable"
            MutationDurabilityResult.Durable
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        val rejected = assertInstanceOf(IntellijWriteProtocolResult.RejectedBeforeMutation::class.java, result)
        assertEquals(SourceWriteFailure.PREIMAGE_CHANGED, rejected.failure)
        assertEquals(listOf("precondition-rejected"), events)
        assertEquals(userText, session.text)
    }

    @Test
    fun `durability rejection never restores a document changed after mutation`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events)
        val userText = "fun service() = 42\n"
        val barrier = MutationDurabilityBarrier {
            session.text = userText
            events += "durable-rejected"
            MutationDurabilityResult.Rejected(MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED)
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        assertInstanceOf(IntellijWriteProtocolResult.RecoveryRequired::class.java, result)
        assertEquals(listOf("insert", "durable-rejected"), events)
        assertEquals(userText, session.text)
    }

    @Test
    fun `a document changed during durable recording is not saved over`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events)
        val userText = "fun service() = 42\n"
        val barrier = MutationDurabilityBarrier {
            session.text = userText
            events += "durable"
            MutationDurabilityResult.Durable
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        assertInstanceOf(IntellijWriteProtocolResult.RecoveryRequired::class.java, result)
        assertEquals(listOf("insert", "durable"), events)
        assertEquals(userText, session.text)
    }

    @Test
    fun `uncertain mutation retains recovery state without restoration durability or save`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events, uncertainMutation = true)
        val barrier = MutationDurabilityBarrier {
            events += "durable"
            MutationDurabilityResult.Durable
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        val required = assertInstanceOf(IntellijWriteProtocolResult.RecoveryRequired::class.java, result)
        assertEquals(SourceWriteFailure.MUTATION_FAILED, required.failure)
        assertEquals(listOf("insert"), events)
        assertEquals(input.postimageText, session.text)
    }

    @Test
    fun `document mutation becomes durable before physical save`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events)
        val barrier = MutationDurabilityBarrier {
            events += "durable"
            MutationDurabilityResult.Durable
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        assertInstanceOf(IntellijWriteProtocolResult.Applied::class.java, result)
        assertEquals(listOf("insert", "durable", "save", "observe"), events)
        assertEquals(input.postimageText, session.text)
    }

    @Test
    fun `durability rejection restores the in-memory preimage without save`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events)
        val barrier = MutationDurabilityBarrier {
            events += "durable"
            MutationDurabilityResult.Rejected(MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED)
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        val rejected =
            assertInstanceOf(
                IntellijWriteProtocolResult.RejectedAfterRollback::class.java,
                result,
            )
        assertEquals(SourceWriteFailure.DURABILITY_REJECTED, rejected.failure)
        assertEquals(listOf("insert", "durable", "rollback"), events)
        assertEquals(input.preimageText, session.text)
    }

    @Test
    fun `fault after durability is recovery required and never reported applied`() {
        val events = mutableListOf<String>()
        val session = FakeDocumentSession(input.preimageText, events, saveFails = true)
        val barrier = MutationDurabilityBarrier {
            events += "durable"
            MutationDurabilityResult.Durable
        }

        val result = IntellijSourceWriteProtocol().execute(input, barrier, session)

        val required =
            assertInstanceOf(
                IntellijWriteProtocolResult.RecoveryRequired::class.java,
                result,
            )
        assertEquals(SourceWriteFailure.SAVE_FAILED, required.failure)
        assertEquals(listOf("insert", "durable", "save"), events)
        assertTrue(result !is IntellijWriteProtocolResult.Applied)
    }

    @Test
    fun `rename durability rejection restores unrelated code and exact preimage`() {
        val rename =
            IntellijMutationInput(
                sourcePath = "/workspace/app/src/main/kotlin/sample/Service.kt",
                preimageText = "val unrelated = 1\nfun service() = unrelated\n",
                postimageText = "val unrelated = 1\nfun renamedService() = unrelated\n",
                mutations = listOf(IntellijTextMutation(22, 29, "renamedService")),
            )
        val session = FakeDocumentSession(rename.preimageText, mutableListOf())

        val result =
            IntellijSourceWriteProtocol()
                .execute(
                    rename,
                    MutationDurabilityBarrier {
                        MutationDurabilityResult.Rejected(MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED)
                    },
                    session,
                )

        assertInstanceOf(IntellijWriteProtocolResult.RejectedAfterRollback::class.java, result)
        assertEquals(rename.preimageText, session.text)
    }

    @Test
    fun `declaration replacement durability rejection restores the exact preimage`() {
        val replacement =
            IntellijMutationInput(
                sourcePath = "/workspace/app/src/main/kotlin/sample/Service.kt",
                preimageText = "val unrelated = 1\nfun service() = 0\n",
                postimageText = "val unrelated = 1\nfun service() = 1\n",
                mutations = listOf(IntellijTextMutation(18, 35, "fun service() = 1")),
            )
        val session = FakeDocumentSession(replacement.preimageText, mutableListOf())

        val result =
            IntellijSourceWriteProtocol()
                .execute(
                    replacement,
                    MutationDurabilityBarrier {
                        MutationDurabilityResult.Rejected(MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED)
                    },
                    session,
                )

        assertInstanceOf(IntellijWriteProtocolResult.RejectedAfterRollback::class.java, result)
        assertEquals(replacement.preimageText, session.text)
    }
}

private class FakeDocumentSession(
    initialText: String,
    private val events: MutableList<String>,
    private val saveFails: Boolean = false,
    private val rejectedMutationText: String? = null,
    private val uncertainMutation: Boolean = false,
) : IntellijDocumentMutationSession {
    var text: String = initialText

    override fun currentText(): String = text

    override fun mutate(input: IntellijMutationInput): IntellijDocumentMutationResult {
        rejectedMutationText?.let { userText ->
            events += "precondition-rejected"
            text = userText
            return IntellijDocumentMutationResult.RejectedBeforeMutation(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        events += "insert"
        text = input.postimageText
        if (uncertainMutation) {
            return IntellijDocumentMutationResult.EffectUncertain(SourceWriteFailure.MUTATION_FAILED)
        }
        return IntellijDocumentMutationResult.Completed
    }

    override fun restore(preimageText: String): IntellijSessionStepResult {
        events += "rollback"
        text = preimageText
        return IntellijSessionStepResult.Completed
    }

    override fun save(): IntellijSessionStepResult {
        events += "save"
        return if (saveFails) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.SAVE_FAILED)
        } else {
            IntellijSessionStepResult.Completed
        }
    }

    override fun observe(): IntellijPhysicalSourceObservation {
        events += "observe"
        return IntellijPhysicalSourceObservation.Observed(
            text.toByteArray(),
            setOf(inputPath()),
        )
    }

    private fun inputPath(): String = "/workspace/app/src/main/kotlin/sample/Service.kt"
}
