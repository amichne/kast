package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeEndpointLocationTest {
    @Test
    fun `exact root deterministically owns one exclusive endpoint location`() {
        val directory = directory("/tmp/kast-ide")
        val root = root("/Users/example/code/kast")

        val first = location(directory, root)
        val repeated = location(directory, root)

        assertEquals(first.stateDirectoryPath, repeated.stateDirectoryPath)
        assertEquals(first.socketPath, repeated.socketPath)
        assertEquals(first.descriptorPath, repeated.descriptorPath)
        assertTrue(first.socketPath.value.startsWith(first.stateDirectoryPath.value))
        assertEquals("${first.socketPath.value}.endpoint.json", first.descriptorPath.value)
        assertTrue(first.socketPath.value.toByteArray().size <= 103)
    }

    @Test
    fun `different exact roots cannot select the same endpoint namespace`() {
        val directory = directory("/tmp/kast-ide")

        val first = location(directory, root("/workspace/first"))
        val second = location(directory, root("/workspace/second"))

        assertNotEquals(first.stateDirectoryPath, second.stateDirectoryPath)
        assertNotEquals(first.socketPath, second.socketPath)
        assertNotEquals(first.descriptorPath, second.descriptorPath)
    }

    @Test
    fun `directory refinement closes invalid and overlong locations`() {
        assertEquals(
            IdeEndpointSocketDirectoryFailure.NOT_ABSOLUTE,
            rejectedDirectory("relative"),
        )
        assertEquals(
            IdeEndpointSocketDirectoryFailure.NOT_NORMALIZED,
            rejectedDirectory("/tmp/../kast"),
        )
        assertEquals(
            IdeEndpointSocketDirectoryFailure.TOO_LONG,
            rejectedDirectory("/" + "x".repeat(64)),
        )
    }

    private fun directory(raw: String): IdeEndpointSocketDirectory = when (
        val parsed = IdeEndpointSocketDirectory.parse(raw)
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("directory rejected: ${parsed.failure}")
    }

    private fun root(raw: String): IdeEndpointCanonicalRoot = when (
        val parsed = IdeEndpointCanonicalRoot.parse(raw)
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("root rejected: ${parsed.failure}")
    }

    private fun rejectedDirectory(raw: String): IdeEndpointSocketDirectoryFailure = when (
        val parsed = IdeEndpointSocketDirectory.parse(raw)
    ) {
        is Refinement.Refined -> error("directory unexpectedly admitted: ${parsed.value}")
        is Refinement.Rejected -> parsed.failure
    }

    private fun location(
        directory: IdeEndpointSocketDirectory,
        root: IdeEndpointCanonicalRoot,
    ): IdeEndpointLocation = when (val located = IdeEndpointLocation.locate(directory, root)) {
        is Refinement.Refined -> located.value
        is Refinement.Rejected -> error("location rejected: ${located.failure}")
    }
}
