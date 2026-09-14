package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadRecoveryTest {
    @Test
    fun `unavailable Gradle model retains rejection and points to observed readiness`() {
        val document = Json.parseToJsonElement(HostedQueryWire.encode(HostedQueryResult.Rejected(
            HostedQueryFailure.ProjectAdmission(ExistingProjectAdmissionFailure.GradleModelUnavailable),
            HostedQueryStage.PROJECT_ADMISSION,
        ))).jsonObject
        assertEquals("rejected", document.getValue("outcome").jsonPrimitive.content)
        assertEquals("GRADLE_MODEL_UNAVAILABLE", document.getValue("detail").jsonPrimitive.content)
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("after_state_change", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals("IDE_STATUS", recovery.getValue("check").jsonPrimitive.content)
        assertEquals("CACHED_GRADLE_MODEL_AVAILABLE", recovery.getValue("required").jsonPrimitive.content)
        assertEquals("OBSERVE_IDE_GRADLE_MODEL", recovery.getValue("remediation").jsonPrimitive.content)
    }
}
