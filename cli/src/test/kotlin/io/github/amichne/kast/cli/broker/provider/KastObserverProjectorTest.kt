package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastObserverProjectorTest {
    @Test
    fun `applied change retains a native diff and rejects escaped paths`() {
        val presentation = project(KastObserverFixtures.changeApply)
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
                KastObserverFixtures.changeApply.replace(
                    "cli/src/main/kotlin/sample/EventConsumer.kt",
                    "../outside.kt",
                ),
            ),
        )
    }

    private fun project(document: String): ObserverPresentation = KastObserverProjector.project(
        checkNotNull(KastOperationId.admit("change.apply")),
        KastInvocationOutput(
            Json.parseToJsonElement(document).jsonObject,
            success = true,
            observerDirectory = checkNotNull(
                CanonicalBrokerDirectory.admit(Path.of(".").toRealPath()),
            ),
        ),
    )
}
