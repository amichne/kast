package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.ObserverPresentation
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastObserverProjectorTest {
    private val live =
        Json.parseToJsonElement(
                """{"root":"/workspace","host":"00000000-0000-0000-0000-000000000001","epoch":7,"contentView":"SAVED_PSI_COMMITTED","version":1}"""
            )
            .jsonObject

    @Test
    fun `live read previews retain the saved content basis without inventing a generation`() {
        for ((operation, raw) in
            listOf(
                "symbol.discover" to KastObserverFixtures.symbolDiscovery,
                "source.read" to KastObserverFixtures.sourceRead,
            )) {
            val envelope = withLiveEvidence(raw)
            val presentation = project(operation, envelope.toString()) as ObserverPresentation.Markdown
            assertTrue(presentation.source.value.contains("Live IDE evidence"))
            assertTrue(presentation.source.value.contains("saved, committed content"))
            assertTrue(presentation.source.value.contains("epoch 7"))
            assertTrue(!presentation.source.value.contains("generation 7"))
        }
        val query =
            """{"status":"completed","document":{"operation":"query.run","status":"complete","items":[],"failures":[]}}"""
        val presentation = project("query.run", withLiveEvidence(query).toString()) as ObserverPresentation.Markdown
        assertTrue(presentation.source.value.contains("0 query results"))
        assertTrue(presentation.source.value.contains("Live IDE evidence"))
    }

    @Test
    fun `observer rejects mixed live and published evidence or mismatched snapshot authority`() {
        val valid = withLiveEvidence(KastObserverFixtures.sourceRead)
        val document = valid.getValue("document").jsonObject
        val snapshot = document.getValue("snapshot").jsonObject
        val mutations =
            listOf(
                JsonObject(snapshot + ("generation" to JsonPrimitive(7))),
                JsonObject(snapshot + ("canonicalRoot" to JsonPrimitive("/other"))),
                JsonObject(snapshot + ("live" to JsonObject(live + ("epoch" to JsonPrimitive(8))))),
            )
        for (changed in mutations) {
            val envelope = JsonObject(valid + ("document" to JsonObject(document + ("snapshot" to changed))))
            assertEquals(ObserverPresentation.None, project("source.read", envelope.toString()))
        }
        for (changed in
            listOf(
                JsonObject(live + ("contentView" to JsonPrimitive("UNKNOWN"))),
                JsonObject(live + ("version" to JsonPrimitive(2))),
            )) {
            val envelope = JsonObject(valid + ("document" to JsonObject(document + ("live" to changed))))
            assertEquals(ObserverPresentation.None, project("source.read", envelope.toString()))
        }
    }

    private fun withLiveEvidence(raw: String): JsonObject {
        val envelope = Json.parseToJsonElement(raw).jsonObject
        var document = JsonObject(envelope.getValue("document").jsonObject + ("live" to live))
        val snapshot = document["snapshot"] as? JsonObject
        if (snapshot != null)
            document =
                JsonObject(
                    document + ("snapshot" to JsonObject(snapshot - "generation" - "sourceState" + ("live" to live)))
                )
        return JsonObject(envelope + ("document" to document))
    }

    @Test
    fun `applied change retains a native diff and rejects escaped paths`() {
        val presentation = project("change.apply", KastObserverFixtures.changeApply)
        val changes = (presentation as ObserverPresentation.FileChanges).files.entries

        assertEquals(1, changes.size)
        assertEquals("cli/src/main/kotlin/sample/EventConsumer.kt", changes.single().path.value)
        assertEquals(
            "@@ class EventConsumer @@\n-    fun consume() = old()\n+    fun consume() = new()",
            changes.single().diff.value,
        )
        assertEquals(
            ObserverPresentation.None,
            project(
                "change.apply",
                KastObserverFixtures.changeApply.replace(
                    "cli/src/main/kotlin/sample/EventConsumer.kt",
                    "../outside.kt",
                ),
            ),
        )
    }

    @Test
    fun `planned change preserves its exact apply identity in the expandable preview`() {
        val presentation = project("change.plan", KastObserverFixtures.changePlan) as ObserverPresentation.Markdown

        assertTrue("Kast · change plan" in presentation.source.value)
        assertTrue("EventConsumer.kt" in presentation.source.value)
        assertTrue("```diff" in presentation.source.value)
        assertTrue("Plan identity: `plan:opaque`" in presentation.source.value)
    }

    @Test
    fun `recovery renders its finite outcome without opaque identity`() {
        val presentation =
            project("change.recover", KastObserverFixtures.changeRecover) as ObserverPresentation.Markdown

        assertTrue("Kast · recovery" in presentation.source.value)
        assertTrue("rolled back" in presentation.source.value)
    }

    private fun project(operation: String, document: String): ObserverPresentation =
        KastObserverProjector.project(
            checkNotNull(KastOperationId.admit(operation)),
            KastInvocationOutput(
                Json.parseToJsonElement(document).jsonObject,
                success = true,
                observerDirectory = checkNotNull(CanonicalBrokerDirectory.admit(Path.of(".").toRealPath())),
            ),
        )
}
