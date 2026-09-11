package io.github.amichne.kast.appserver.provider

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastObserverSnapshotMainTest {
    @Test
    fun `manifest demonstrates every canonical operation and native apply diff`(@TempDir temporary: Path) {
        val output = temporary.resolve("observer-presentations.json")

        KastObserverSnapshotMain.main(arrayOf(output.toString()))

        val pages = Json.parseToJsonElement(Files.readString(output)).jsonObject.getValue("pages").jsonArray
        val items = pages.flatMap { page -> page.jsonObject.getValue("items").jsonArray }
        assertEquals(
            setOf(
                "symbol.discover",
                "symbol.inspect",
                "source.read",
                "relation.read",
                "traversal.run",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
            items.map { item -> item.jsonObject.getValue("operation").jsonPrimitive.content }.toSet(),
        )
        assertEquals(9, items.size)
        val apply =
            items
                .single { item ->
                    item.jsonObject.getValue("operation").jsonPrimitive.content == "change.apply"
                }
                .jsonObject
        assertEquals("file-changes", apply.getValue("presentation").jsonPrimitive.content)
        assertTrue(apply.getValue("changes").jsonArray.isNotEmpty())
    }
}
