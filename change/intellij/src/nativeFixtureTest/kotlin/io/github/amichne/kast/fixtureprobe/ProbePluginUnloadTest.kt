package io.github.amichne.kast.fixtureprobe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbePluginUnloadTest {
    private val digest = ProbeDigest.observe(byteArrayOf())
    private val evidence =
        ProbeEvidence(
            digest,
            digest,
            ProbeDocumentState.SAVED_COMMITTED,
            ProbeSyntaxState.CLEAN,
            ProbeUndoState.UNAVAILABLE,
            emptyList(),
        )

    @Test
    fun unsupportedCheckCannotStartEffectAndRetainsItsStage() {
        val observed = mutableListOf<ProbeUnloadObservation>()
        val result =
            completeProbePluginUnload(
                evidence,
                check = { ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_UNSUPPORTED) },
                unload = { error("Refused preflight must not unload") },
                observe = observed::add,
            )
        assertEquals(
            ProbeFailure.PLUGIN_UNLOAD_UNSUPPORTED,
            assertInstanceOf(ProbeExecution.Rejected::class.java, result).failure,
        )
        assertEquals(
            listOf(
                ProbeUnloadObservation.Started(ProbeUnloadStage.CHECK),
                ProbeUnloadObservation.Rejected(ProbeUnloadStage.CHECK, ProbeFailure.PLUGIN_UNLOAD_UNSUPPORTED),
            ),
            observed,
        )
        val encoded =
            Json.parseToJsonElement(Json.encodeToString(ProbeUnloadObservation.serializer(), observed.last()))
                .jsonObject
        assertEquals(setOf("type", "stage", "failure"), encoded.keys)
        assertEquals("rejected", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("CHECK", encoded.getValue("stage").jsonPrimitive.content)
        assertEquals("PLUGIN_UNLOAD_UNSUPPORTED", encoded.getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun unsuccessfulEffectRetainsUncertaintyAndFiniteFailure() {
        val observed = mutableListOf<ProbeUnloadObservation>()
        val result =
            completeProbePluginUnload(
                evidence,
                check = { ProbeResult.Accepted(Unit) },
                unload = { ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_REJECTED) },
                observe = observed::add,
            )
        assertEquals(
            ProbeFailure.PLUGIN_UNLOAD_REJECTED,
            assertInstanceOf(ProbeExecution.EffectUncertain::class.java, result).failure,
        )
        assertEquals(
            listOf(
                ProbeUnloadObservation.Started(ProbeUnloadStage.CHECK),
                ProbeUnloadObservation.Completed(ProbeUnloadStage.CHECK),
                ProbeUnloadObservation.Started(ProbeUnloadStage.EFFECT),
                ProbeUnloadObservation.Rejected(ProbeUnloadStage.EFFECT, ProbeFailure.PLUGIN_UNLOAD_REJECTED),
            ),
            observed,
        )
    }

    @Test
    fun successfulCheckAndEffectPublishUnloadedWithOriginalEvidence() {
        val observed = mutableListOf<ProbeUnloadObservation>()
        val result =
            completeProbePluginUnload(
                evidence,
                check = { ProbeResult.Accepted(Unit) },
                unload = { ProbeResult.Accepted(Unit) },
                observe = observed::add,
            )
        val completed = assertInstanceOf(ProbeExecution.LifecycleCompleted::class.java, result)
        assertEquals(evidence, completed.evidence)
        assertEquals(ProbePluginLifecycle.UNLOADED, completed.lifecycle)
        assertEquals(
            listOf(
                ProbeUnloadObservation.Started(ProbeUnloadStage.CHECK),
                ProbeUnloadObservation.Completed(ProbeUnloadStage.CHECK),
                ProbeUnloadObservation.Started(ProbeUnloadStage.EFFECT),
                ProbeUnloadObservation.Completed(ProbeUnloadStage.EFFECT),
            ),
            observed,
        )
        val encoded =
            Json.parseToJsonElement(Json.encodeToString(ProbeUnloadObservation.serializer(), observed.last()))
                .jsonObject
        assertEquals(setOf("type", "stage"), encoded.keys)
        assertEquals("completed", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("EFFECT", encoded.getValue("stage").jsonPrimitive.content)
    }
}
