package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class DeadHostedEndpointOwnerTest {
    @Test
    fun `retirement rejects a reused active or unobservable process`(@TempDir directory: Path) {
        val root =
            assertInstanceOf<Refinement.Refined<CanonicalWorkspaceRoot>>(
                    CanonicalWorkspaceRoot.fromCanonicalPath(directory.toRealPath())
                )
                .value
        val socket = directory.resolve("host.sock")
        val descriptor = directory.resolve("endpoint.json")
        val content =
            Json.encodeToString(
                HostedEndpointDescriptorDocument.create(
                    root,
                    socket,
                    42,
                    UUID.randomUUID().toString(),
                    HostedEndpointAdvertisement.SEMANTIC,
                )
            )
        Files.writeString(descriptor, content)
        for (current in
            listOf(
                HostedEndpointOwnerProcess.Active,
                HostedEndpointOwnerProcess.Unavailable,
                HostedEndpointOwnerProcess.Zombie(101),
            )) {
            val script = ArrayDeque(listOf(HostedEndpointOwnerProcess.Zombie(100), current))
            val owner =
                assertInstanceOf<Refinement.Refined<DeadHostedEndpointOwner>>(
                        DeadHostedEndpointOwner.read(descriptor, root, socket) { pid ->
                            assertEquals(42L, pid)
                            assertTrue(script.isNotEmpty(), "Unexpected process observation")
                            script.removeFirst()
                        }
                    )
                    .value
            assertFalse(owner.remainsExited())
            assertTrue(script.isEmpty())
            assertEquals(content, Files.readString(descriptor))
        }
    }

    @Test
    fun `owner diagnostics preserve every finite process state without payloads`() {
        for (state in HostedEndpointOwnerState.entries) {
            val encoded =
                Json.parseToJsonElement(Json.encodeToString(HostedEndpointOwnerObservation(42, state))).jsonObject
            assertEquals(setOf("pid", "state"), encoded.keys)
            assertEquals("42", encoded.getValue("pid").jsonPrimitive.content)
            assertEquals(state.name, encoded.getValue("state").jsonPrimitive.content)
        }
    }
}
