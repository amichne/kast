package io.github.amichne.kast.cli.publicshell

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

/** Actual native writer with an unread pipe, established syscall evidence and exact public-child ownership. */
@Tag("public-launcher")
class PublicOutputCancellationTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path

    @Test
    fun `both signals settle the proven native blocked writer without downstream reads`() {
        NativePublicFixture(temporary).use { fixture ->
            assertDrainedControl(fixture)
            val complete = Files.readAllBytes(temporary.resolve("control.stdout"))
            for ((signal, exit) in listOf("INT" to 130, "TERM" to 143)) {
                val label = "blocked-$signal"
                val parent = fixture.start(label, metadataArguments(), captureOutput = false)
                parent.outputStream.close()
                val child = fixture.awaitQueryChild(parent)
                // No stdout consumer has run. Sampling must observe this worker inside write, not just join.
                awaitNativePipeStack(child, temporary, "write")
                signalNativeProcess(parent, signal)
                fixture.assertExit(parent, exit, label)
                fixture.assertReaped(child)
                // Only after both exits may we inspect the abandoned pipe prefix.
                val prefix = parent.inputStream.readAllBytes()
                Files.write(temporary.resolve("$label.prefix"), prefix)
                assertTrue(prefix.isNotEmpty(), "No bytes reached the blocked stdout pipe")
                assertTrue(prefix.size < complete.size, "Blocked output unexpectedly completed")
                assertTrue(
                    complete.copyOf(prefix.size).contentEquals(prefix),
                    "Output was not the exact control prefix",
                )
            }
        }
    }

    @Test
    fun `closing the proven blocked native stdout pipe selects IO failure and reaps the child`() {
        NativePublicFixture(temporary).use { fixture ->
            val parent = fixture.start("closed-consumer", metadataArguments(), captureOutput = false)
            parent.outputStream.close()
            val child = fixture.awaitQueryChild(parent)
            awaitNativePipeStack(child, temporary, "write")
            parent.inputStream.close()
            fixture.assertExit(parent, 5, "closed-consumer")
            fixture.assertReaped(child)
        }
    }

    private fun assertDrainedControl(fixture: NativePublicFixture) {
        val parent = fixture.start("control", metadataArguments())
        parent.outputStream.close()
        fixture.assertExit(parent, 0, "control")
        val bytes = Files.readAllBytes(temporary.resolve("control.stdout"))
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(bytes.size > 1_048_576, "Output control no longer exceeds a pipe's capacity")
        assertEquals(1, text.count { it == '\n' })
        assertTrue(text.endsWith("\n"))
        assertEquals(
            "kast.cli.describe.v1",
            Json.parseToJsonElement(text).jsonObject.getValue("schema").jsonPrimitive.content,
        )
        Files.writeString(temporary.resolve("control.txt"), "exit=0\nbytes=${bytes.size}\n")
    }

    private fun metadataArguments(): List<String> = listOf("describe", "query", "--section", "output")
}
