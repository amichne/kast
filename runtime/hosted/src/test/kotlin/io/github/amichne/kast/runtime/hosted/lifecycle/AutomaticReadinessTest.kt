package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryRejectionDocument
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRecovery
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutomaticReadinessTest {
    @Test
    fun `only proven missing Gradle model selects automatic reload`() {
        assertEquals(
            AutomaticReadinessAction.Ready,
            HostedReadinessDocument.AdmissionReady.automaticAction(),
        )
        assertEquals(
            AutomaticReadinessAction.ReloadModel,
            unavailable(HostedReadRecovery.GradleModel.Required).automaticAction(),
        )
        assertEquals(
            AutomaticReadinessAction.Wait,
            unavailable(HostedReadRecovery.Indexing.Required).automaticAction(),
        )
        assertEquals(
            AutomaticReadinessAction.Wait,
            unavailable(HostedReadRecovery.ReviewFailure).automaticAction(),
        )
        assertEquals(
            AutomaticReadinessAction.UnsavedDocuments,
            unavailable(HostedReadRecovery.SaveSource.Required).automaticAction(),
        )
    }

    private fun unavailable(recovery: HostedReadRecovery) =
        HostedReadinessDocument.Unavailable(
            HostedQueryRejectionDocument(
                failure = "PROJECT_ADMISSION_REJECTED",
                detail = JsonPrimitive("GRADLE_MODEL_UNAVAILABLE"),
                stage = "PROJECT_ADMISSION",
                recovery = recovery,
            )
        )
}
