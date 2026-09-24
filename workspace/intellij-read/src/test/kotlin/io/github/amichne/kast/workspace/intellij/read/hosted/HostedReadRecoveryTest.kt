package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadRecoveryTest {
    @Test
    fun `capture timeout identifies the host preparation deadline`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(HostedQueryFailure.BUDGET_EXCEEDED, HostedQueryStage.MODEL_CAPTURE)
                    )
                )
                .jsonObject
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("increase_host_deadline", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals("MODEL_CAPTURE", document.getValue("stage").jsonPrimitive.content)
    }

    @Test
    fun `exhaustion and cancellation have different actionable recovery`() {
        for ((failure, expected) in
            listOf(
                HostedQueryFailure.BUDGET_EXCEEDED to "reduce_read_work",
                HostedQueryFailure.CANCELLED to "cancelled",
                HostedQueryFailure.DIRTY_DOCUMENTS to "save_source",
                HostedQueryFailure.BUSY to "wait_for_capacity",
            )) {
            val document =
                Json.parseToJsonElement(
                        HostedQueryWire.encode(HostedQueryResult.Rejected(failure, HostedQueryStage.SEMANTIC_READ))
                    )
                    .jsonObject
            assertEquals(expected, document.getValue("recovery").jsonObject.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test
    fun `unavailable Gradle model retains rejection and points to observed readiness`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(
                            HostedQueryFailure.ProjectAdmission(ExistingProjectAdmissionFailure.GradleModelUnavailable),
                            HostedQueryStage.PROJECT_ADMISSION,
                        )
                    )
                )
                .jsonObject
        assertEquals("rejected", document.getValue("outcome").jsonPrimitive.content)
        assertEquals("GRADLE_MODEL_UNAVAILABLE", document.getValue("detail").jsonPrimitive.content)
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("after_state_change", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals("IDE_STATUS", recovery.getValue("check").jsonPrimitive.content)
        assertEquals("CACHED_GRADLE_MODEL_COMPLETE", recovery.getValue("required").jsonPrimitive.content)
        assertEquals("OBSERVE_IDE_GRADLE_MODEL", recovery.getValue("remediation").jsonPrimitive.content)
    }
}
