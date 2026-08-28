package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.runtime.ide.read.preparation.HostedIdeReadRuntimePreparationFailure
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCaptureFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeEndpointModelCaptureAdmissionTest {
    @Test
    fun `partial read runtime remains fail closed and retryable during model churn`() {
        assertEquals(
            IdeEndpointStartup.Deferred(
                IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
            ),
            IdeEndpointReadRuntimeAdmission.admit(
                HostedIdeReadRuntimePreparationFailure.PARTIAL_RUNTIME,
            ),
        )
    }

    @Test
    fun `unsettled Gradle module ownership remains fail closed and retryable`() {
        assertEquals(
            IdeEndpointStartup.Deferred(
                IdeEndpointDeferredReadiness.GRADLE_MODEL_INCOMPLETE,
            ),
            IdeEndpointModelCaptureAdmission.admit(
                setOf(DetachedModelCaptureFailure.NOT_GRADLE_OWNED),
            ),
        )
    }

    @Test
    fun `terminal failure cannot hide behind unsettled Gradle module ownership`() {
        val failures = setOf(
            DetachedModelCaptureFailure.NOT_GRADLE_OWNED,
            DetachedModelCaptureFailure.MODULE_DISPOSED,
        )

        assertEquals(
            IdeEndpointStartup.Rejected(
                IdeEndpointStartupFailure.ProjectModelRejected(failures),
            ),
            IdeEndpointModelCaptureAdmission.admit(failures),
        )
    }
}
