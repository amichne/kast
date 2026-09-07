package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastObserverProjectorTest {
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
        val presentation = project("change.plan", KastObserverFixtures.changePlan)
            as ObserverPresentation.Markdown

        assertTrue("Kast · change plan" in presentation.source.value)
        assertTrue("EventConsumer.kt" in presentation.source.value)
        assertTrue("```diff" in presentation.source.value)
        assertTrue("Plan identity: `plan:opaque`" in presentation.source.value)
    }

    @Test
    fun `recovery renders its finite outcome without opaque identity`() {
        val presentation = project("change.recover", KastObserverFixtures.changeRecover)
            as ObserverPresentation.Markdown

        assertTrue("Kast · recovery" in presentation.source.value)
        assertTrue("rolled back" in presentation.source.value)
    }

    private fun project(operation: String, document: String): ObserverPresentation = KastObserverProjector.project(
        checkNotNull(KastOperationId.admit(operation)),
        KastInvocationOutput(
            Json.parseToJsonElement(document).jsonObject,
            success = true,
            observerDirectory = checkNotNull(
                CanonicalBrokerDirectory.admit(Path.of(".").toRealPath()),
            ),
        ),
    )
}
