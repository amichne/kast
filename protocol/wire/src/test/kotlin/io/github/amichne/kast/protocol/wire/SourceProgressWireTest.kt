package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SourceProgressWireTest {
    private val json = Json { encodeDefaults = true }
    private val nativeToken = "source-read-continuation-v1|" + "a".repeat(64)
    private val outputToken = "source-output:v1:00000000-0000-0000-0000-000000000001"

    @Test
    fun `native and retained checkpoints encode their scope and supported action independently`() {
        verify(
            SourceQualifiedProgressDocument.Resumable(
                SourceCheckpointDocument.Upstream(text(nativeToken)),
                ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            ),
            ExpectedProgress.Resumable(ExpectedCheckpoint.Upstream(nativeToken), "increase_execution_budget"),
        )
        for ((coverage, expected) in coverageStates()) {
            verify(
                SourceQualifiedProgressDocument.Resumable(
                    SourceCheckpointDocument.RetainedOutput(text(outputToken), coverage),
                    ReadResumeActionDocument.RESUME,
                ),
                ExpectedProgress.Resumable(ExpectedCheckpoint.Retained(outputToken, expected), "resume"),
            )
        }
    }

    @Test
    fun `terminal reasons encode explicitly and unknown reasons fail closed`() {
        for ((reason, name) in terminalReasons) {
            verify(SourceQualifiedProgressDocument.TerminalIncomplete(reason), ExpectedProgress.Terminal(name))
        }
        assertInstanceOf(WireDecoding.Rejected::class.java, decode(ExpectedProgress.Terminal("invented")))
    }

    @Test
    fun `checkpoint family and identity cannot contradict the declared scope`() {
        for (checkpoint in
            listOf(
                ExpectedCheckpoint.Upstream(outputToken),
                ExpectedCheckpoint.Upstream("source-read-continuation-v1|short"),
                ExpectedCheckpoint.Retained(nativeToken, ExpectedCoverage.Complete),
                ExpectedCheckpoint.Retained("source-output:v1:invalid", ExpectedCoverage.Complete),
            )) assertInstanceOf(
            WireDecoding.Rejected::class.java,
            decode(ExpectedProgress.Resumable(checkpoint, "resume")),
        )
    }

    @Test
    fun `terminal text explanation requires matching qualification evidence`() {
        val boundary =
            ExpectedSourceQualification(
                limitations = listOf("work-limit-reached"),
                progress = ExpectedProgress.Terminal("text-projection-withheld"),
            )
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalSourceReadSerializers.qualification.decode(
                json.encodeToJsonElement(ExpectedSourceQualification.serializer(), boundary),
                WireValueRole.QUALIFICATION,
            ),
        )
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            decode(ExpectedProgress.Resumable(ExpectedCheckpoint.Upstream(nativeToken), "invented")),
        )
    }

    private fun verify(progress: SourceQualifiedProgressDocument, expected: ExpectedProgress) {
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCountDocument.parse(0).proven(),
                    listOf(
                        SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED,
                        SourceReadLimitationDocument.WORK_LIMIT_REACHED,
                    ),
                    progress,
                )
                .proven()
        val element =
            json.encodeToJsonElement(
                ExpectedSourceQualification.serializer(),
                ExpectedSourceQualification(progress = expected),
            )
        assertEquals(
            WireValueEncoding.Encoded(element),
            CanonicalSourceReadSerializers.qualification.encode(qualification, WireValueRole.QUALIFICATION),
        )
        assertEquals(
            WireDecoding.Decoded(qualification),
            CanonicalSourceReadSerializers.qualification.decode(element, WireValueRole.QUALIFICATION),
        )
    }

    private fun decode(progress: ExpectedProgress) =
        CanonicalSourceReadSerializers.qualification.decode(
            json.encodeToJsonElement(
                ExpectedSourceQualification.serializer(),
                ExpectedSourceQualification(progress = progress),
            ),
            WireValueRole.QUALIFICATION,
        )

    private fun coverageStates(): List<Pair<SourcePreparedCoverageDocument, ExpectedCoverage>> =
        listOf(
            SourcePreparedCoverageDocument.Complete to ExpectedCoverage.Complete,
            SourcePreparedCoverageDocument.Resumable to ExpectedCoverage.Resumable,
        ) +
            terminalReasons.map { (reason, name) ->
                SourcePreparedCoverageDocument.TerminalIncomplete(reason) to ExpectedCoverage.Terminal(name)
            }

    private val terminalReasons =
        mapOf(
            SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE to "upstream-incomplete",
            SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD to "text-projection-withheld",
        )

    private fun text(value: String) = ProtocolText.parse(value).proven()

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}

@Serializable
private data class ExpectedSourceQualification(
    val knownMinimumEntityCount: Int = 0,
    val limitations: List<String> = listOf("text-byte-limit-reached", "work-limit-reached"),
    val progress: ExpectedProgress,
)

@Serializable
private sealed interface ExpectedProgress {
    @Serializable
    @SerialName("resumable")
    data class Resumable(val checkpoint: ExpectedCheckpoint, @SerialName("next_action") val nextAction: String) :
        ExpectedProgress

    @Serializable @SerialName("terminal_incomplete") data class Terminal(val reason: String) : ExpectedProgress
}

@Serializable
private sealed interface ExpectedCheckpoint {
    @Serializable @SerialName("upstream") data class Upstream(val token: String) : ExpectedCheckpoint

    @Serializable
    @SerialName("retained_output")
    data class Retained(val token: String, val upstream: ExpectedCoverage) : ExpectedCheckpoint
}

@Serializable
private sealed interface ExpectedCoverage {
    @Serializable @SerialName("complete") data object Complete : ExpectedCoverage

    @Serializable @SerialName("resumable") data object Resumable : ExpectedCoverage

    @Serializable @SerialName("terminal_incomplete") data class Terminal(val reason: String) : ExpectedCoverage
}
