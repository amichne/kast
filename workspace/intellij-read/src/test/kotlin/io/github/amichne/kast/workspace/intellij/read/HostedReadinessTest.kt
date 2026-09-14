package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import io.github.amichne.kast.workspace.intellij.read.hosted.observeHostedReadiness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedReadinessTest {
    @Test
    fun `cold model observation recovers without caching rejection or installing a read authority`() {
        val observation = RecordingProjectObservation(gradleModelState = ExistingProjectGradleModelState.UNAVAILABLE)
        val project = opaqueProject()
        val observe = {
            ExistingProjectValidation.validateObserved(
                project,
                FIXTURE_ROOT,
                FIXTURE_COMPATIBILITY,
                FIXTURE_COMPATIBILITY_POLICY,
                observation,
            )
        }
        val cold = observeHostedReadiness(observe)
        assertInstanceOf(HostedReadinessDocument.Unavailable::class.java, cold)
        val json = Json { encodeDefaults = true }.encodeToJsonElement<HostedReadinessDocument>(cold).jsonObject
        assertEquals("unavailable", json.getValue("status").jsonPrimitive.content)
        assertEquals(
            "GRADLE_MODEL_UNAVAILABLE",
            json.getValue("rejection").jsonObject.getValue("detail").jsonPrimitive.content,
        )
        assertEquals(ExistingProjectObservationStage.entries.take(5), observation.observedStages)

        observation.observedStages.clear()
        observation.gradleModelState = ExistingProjectGradleModelState.COMPLETE
        assertSame(HostedReadinessDocument.AdmissionReady, observeHostedReadiness(observe))
        assertEquals(ExistingProjectObservationStage.entries, observation.observedStages)
        // The identical existing-project query admission succeeds after the external model changed.
        assertInstanceOf(
            ExistingProjectAdmission.Admitted::class.java,
            AdmittedIdeProject.admitObserved(
                project,
                FIXTURE_ROOT,
                FIXTURE_COMPATIBILITY,
                FIXTURE_COMPATIBILITY_POLICY,
                observation,
                FIXTURE_EPOCH_SOURCE_FACTORY,
            ),
        )
    }

    @Test
    fun `readiness never guesses importing or hides observed indexing rejection`() {
        val result = observeHostedReadiness {
            ExistingProjectValidation.Rejected(ExistingProjectAdmissionFailure.DumbMode)
        }
        val json = Json { encodeDefaults = true }.encodeToJsonElement<HostedReadinessDocument>(result).jsonObject
        assertEquals("unavailable", json.getValue("status").jsonPrimitive.content)
        assertEquals("DUMB_MODE", json.getValue("rejection").jsonObject.getValue("detail").jsonPrimitive.content)
        assertFalse(json.toString().contains("importing"))
    }
}
