package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryRejectionDocument
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRecovery
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedReadinessObservationTest {
    @Test
    fun `readiness observation retains both success and unavailable stage evidence`() {
        val unavailable =
            HostedReadinessDocument.Unavailable(
                HostedQueryRejectionDocument(
                    failure = "PROJECT_ADMISSION_REJECTED",
                    detail = JsonPrimitive("GRADLE_MODEL_UNAVAILABLE"),
                    stage = "PROJECT_ADMISSION",
                    recovery = HostedReadRecovery.GradleModel.Required,
                )
            )
        for ((readiness, outcome) in
            listOf(
                HostedReadinessDocument.AdmissionReady to HostedEndpointOutcome.COMPLETED,
                unavailable to HostedEndpointOutcome.REJECTED,
            )) {
            val observations = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
            val observer = HostedEndpointObserver { stage, state -> observations += stage to state }
            assertSame(readiness, observeEndpointReadiness(observer) { readiness })
            assertEquals(
                listOf(
                    HostedEndpointStage.READINESS to HostedEndpointOutcome.STARTED,
                    HostedEndpointStage.READINESS to outcome,
                ),
                observations,
            )
        }
    }
}
