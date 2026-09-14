package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

@ResourceLock(Resources.SYSTEM_ERR)
class RuntimeIdentityObservationTest {
    @Test
    fun `passive identity inspection keeps successful inventory counters off stderr`(@TempDir root: Path) {
        val product = payload(root)
        val observed = capture { BrokerInstallationState.observe(product) }
        assertEquals(Refinement.Rejected(InstallationStateFailure.EPOCH_ABSENT), observed.value)
        assertEquals("", observed.stderr)
        assertFalse(Files.exists(product.resolve("state")))
    }

    @Test
    fun `passive inspection of an admitted owner is quiet and leaves epoch unchanged`(@TempDir root: Path) {
        val product = payload(root)
        val admitted = capture { BrokerInstallationState.admit(product) }
        assertTrue(admitted.value is Refinement.Refined)
        val epoch = Files.readString(product.resolve("state/epoch.json"))
        val observed = capture { BrokerInstallationState.observe(product) }
        assertEquals(admitted.value, observed.value)
        assertEquals("", observed.stderr)
        assertEquals(epoch, Files.readString(product.resolve("state/epoch.json")))
    }

    @Test
    fun `passive rejected payload still emits bounded finite failure diagnostics`(@TempDir root: Path) {
        val product = payload(root)
        Files.createSymbolicLink(product.resolve("lib/foreign"), product.parent)
        val observed = capture { BrokerInstallationState.observe(product) }
        assertEquals(Refinement.Rejected(InstallationStateFailure.PAYLOAD_REJECTED), observed.value)
        val diagnostic = Json.parseToJsonElement(observed.stderr.trim()).jsonObject
        assertEquals(setOf("boundary", "failure"), diagnostic.keys)
        assertEquals("RUNTIME_IDENTITY", diagnostic.getValue("boundary").jsonPrimitive.content)
        assertEquals("UNSUPPORTED_ENTRY", diagnostic.getValue("failure").jsonPrimitive.content)
        assertFalse(observed.stderr.contains(product.toString()))
    }

    private fun payload(root: Path): Path {
        val product = Files.createDirectory(root.resolve("product"))
        for (tree in listOf("bin", "lib", "share")) Files.createDirectory(product.resolve(tree))
        Files.writeString(product.resolve("bin/kast"), "launcher")
        Files.writeString(product.resolve("lib/control.jar"), "payload")
        return product.toRealPath()
    }

    private fun <Value> capture(action: () -> Value): Captured<Value> {
        val previous = System.err
        val bytes = ByteArrayOutputStream()
        return try {
            PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
                System.setErr(stream)
                Captured(action(), bytes.toString(Charsets.UTF_8))
            }
        } finally {
            System.setErr(previous)
        }
    }

    private data class Captured<Value>(val value: Value, val stderr: String)
}
