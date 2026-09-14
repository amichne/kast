package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlPayloadInventoryTest {
    @Test
    fun `exact traversal boundary succeeds and next file gives a bounded typed failure`(@TempDir root: Path) {
        val physical = root.toRealPath()
        listOf("bin", "lib", "share").forEach { Files.createDirectory(physical.resolve(it)) }
        repeat(ControlDistributionLimits.maximumTraversedEntries - 3) {
            Files.createFile(physical.resolve("share/f-$it"))
        }
        val admitted = ControlPayloadInventory.admit(physical) as ControlInventoryAdmission.Admitted
        assertEquals(ControlDistributionLimits.maximumTraversedEntries, admitted.traversedEntries)
        Files.createFile(physical.resolve("bin/overflow"))
        val rejected = ControlPayloadInventory.admit(physical) as ControlInventoryAdmission.Rejected
        assertEquals(ControlInventoryFailure.LIMIT_EXCEEDED, rejected.failure)
        assertEquals(ControlInventoryResource.TRAVERSED_ENTRIES, rejected.limit?.resource)
        assertEquals(16_385L, rejected.limit?.observedAtLeast)
        val encoded =
            Json.encodeToString(
                ControlInventoryObservation.serializer(),
                ControlInventoryObservation(
                    ControlInventoryBoundary.RUNTIME_IDENTITY,
                    rejected.failure,
                    rejected.limit,
                ),
            )
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("boundary", "failure", "limit"), document.keys)
        assertEquals("LIMIT_EXCEEDED", document.getValue("failure").jsonPrimitive.content)
        assertEquals("16384", document.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.content)
        assertFalse(encoded.contains(root.toString()))
    }

    @Test
    fun `sparse oversized file is rejected before reading and symlink is never followed`(@TempDir root: Path) {
        listOf("bin", "lib", "share").forEach { Files.createDirectory(root.resolve(it)) }
        val large = root.resolve("lib/large")
        java.io.RandomAccessFile(large.toFile(), "rw").use {
            it.setLength(ControlDistributionLimits.maximumPayloadBytes + 1)
        }
        val rejected = ControlPayloadInventory.admit(root) as ControlInventoryAdmission.Rejected
        assertEquals(ControlInventoryResource.PAYLOAD_BYTES, rejected.limit?.resource)
        Files.delete(large)
        Files.createSymbolicLink(large, root.parent)
        assertEquals(
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.UNSUPPORTED_ENTRY),
            ControlPayloadInventory.admit(root),
        )
    }
}
