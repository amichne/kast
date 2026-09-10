package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

class HostedEndpointTest {
    @Test fun `direct supertype accepts a qualified identity and rejects mixed selectors`() {
        assertTrue(HostedRequests.decode("""{"type":"DIRECT_SUPERTYPE","root":"/workspace","qualifiedName":"example.Outer.Child"}""") is Refinement.Refined)
        for (request in listOf(
            """{"type":"DIRECT_SUPERTYPE","root":"/workspace","qualifiedName":"example..Child"}""",
            """{"type":"DIRECT_SUPERTYPE","root":"/workspace","qualifiedName":"example.Child","file":"Child.kt","offset":0}""",
            """{"type":"DIRECT_SUPERTYPE","root":"/workspace","qualifiedName":12}""",
        )) assertEquals(Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST), HostedRequests.decode(request))
    }

    @Test fun `request evidence distinguishes completed transport from rejected frames without semantic dispatch`() = runBlocking {
        val observations = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
        val observer = HostedEndpointObserver { stage, outcome -> observations += stage to outcome }
        val invalid = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(16_385) }
        val rejectedOutput = ByteArrayOutputStream()
        serveHostedConnection(ByteArrayInputStream(invalid.toByteArray()), rejectedOutput, observer) {
            fail("Rejected frames must not reach semantic dispatch")
        }
        assertEquals(listOf(HostedEndpointOutcome.STARTED, HostedEndpointOutcome.REJECTED), observations.map { it.second })
        assertTrue(rejectedOutput.toString(Charsets.UTF_8).contains("REQUEST_TOO_LARGE"))

        observations.clear()
        val admitted = ByteArrayOutputStream().also { HostedFrames.write(it, """{"type":"DESCRIBE","root":"/workspace"}""") }
        var dispatched = 0
        serveHostedConnection(ByteArrayInputStream(admitted.toByteArray()), ByteArrayOutputStream(), observer) {
            assertTrue(it is HostedRequest.Describe)
            dispatched++
            "{}"
        }
        assertEquals(1, dispatched)
        assertEquals(listOf(HostedEndpointOutcome.STARTED, HostedEndpointOutcome.COMPLETED), observations.map { it.second })
        assertTrue(observations.all { it.first == HostedEndpointStage.REQUEST })
    }

    @Test fun `endpoint ownership excludes contenders and permits reattachment only after retirement`() {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        // Keep the socket path within the platform limit independently of the JUnit temp prefix.
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "khe-")
        try {
            val owned = (OwnedHostedEndpoint.open(directory, root) as Refinement.Refined).value
            assertEquals(Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT), OwnedHostedEndpoint.open(directory, root))
            assertTrue(Files.exists(owned.socket))
            owned.close()
            assertFalse(Files.exists(directory.resolve("endpoint.json")))
            assertFalse(Files.exists(owned.socket))
            (OwnedHostedEndpoint.open(directory, root) as Refinement.Refined).value.close()
        } finally { Files.deleteIfExists(directory.resolve("owner.lock")); Files.delete(directory) }
    }

    @Test fun `unowned socket artifacts remain untouched on admission failure`() {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "khe-")
        try {
            val descriptor = directory.resolve("endpoint.json")
            Files.writeString(descriptor, "unowned")
            assertEquals(Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT), OwnedHostedEndpoint.open(directory, root))
            assertEquals("unowned", Files.readString(descriptor))
        } finally {
            Files.deleteIfExists(directory.resolve("endpoint.json")); Files.deleteIfExists(directory.resolve("owner.lock")); Files.delete(directory)
        }
    }
    @Test fun `framing rejects oversized and incomplete requests without invoking semantic work`() {
        val oversized = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(16_385) }
        assertEquals(Refinement.Rejected(HostedEndpointFailure.REQUEST_TOO_LARGE), HostedFrames.read(ByteArrayInputStream(oversized.toByteArray())))
        val truncated = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(3); writeByte(1) } }
        assertEquals(Refinement.Rejected(HostedEndpointFailure.REQUEST_INCOMPLETE), HostedFrames.read(ByteArrayInputStream(truncated.toByteArray())))
    }

    @Test fun `wire parser closes operation and field shapes before semantic admission`() {
        assertTrue(HostedRequests.decode("""{"type":"CLASS_LOOKUP","root":"/workspace","name":"Refinement"}""") is Refinement.Refined)
        for (request in listOf("{}", """{"type":"CLASS_LOOKUP","root":"/workspace","name":1}""", """{"type":"IMPORT","root":"/workspace"}""", """{"type":"DESCRIBE","root":"/workspace","extra":true}""",
            """{"type":"DESCRIBE","type":"DESCRIBE","root":"/workspace"}""", """{type:"DESCRIBE",root:"/workspace"}""", """{"type":"DESCRIBE","root":"/workspace"}{}""")) {
            assertEquals(Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST), HostedRequests.decode(request))
        }
    }
}
