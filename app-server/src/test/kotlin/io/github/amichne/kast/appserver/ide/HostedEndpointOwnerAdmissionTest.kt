package io.github.amichne.kast.appserver.ide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostedEndpointOwnerAdmissionTest {
    @TempDir lateinit var temporary: Path
    private val json = Json { encodeDefaults = true }

    @Test
    fun `absent legacy owner excludes missing physical root before current descriptor admission`() {
        val events = ArrayList<HostedAdmissionEvidence>()
        val result = scan(HostedEndpointOwnerObservation.ABSENT, events)
        assertEquals(HostedServicesObservation.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE), result)
        assertTrue(events.contains(HostedAdmissionEvidence.Excluded(HostedAdmissionStage.OWNER_LIVENESS)))
        assertTrue(
            events.none { event ->
                event.stage() == HostedAdmissionStage.CURRENT_DESCRIPTOR ||
                    event.stage() == HostedAdmissionStage.ROOT_ADMISSION
            }
        )
    }

    @Test
    fun `alive identical legacy descriptor fails current schema before physical root`() {
        val events = ArrayList<HostedAdmissionEvidence>()
        assertEquals(
            HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
            scan(HostedEndpointOwnerObservation.ALIVE, events),
        )
        assertEquals(
            HostedAdmissionEvidence.Rejected(
                HostedAdmissionStage.CURRENT_DESCRIPTOR,
                ExistingIdeFailure.DESCRIPTOR_REJECTED,
            ),
            events.last(),
        )
        assertTrue(events.none { it.stage() == HostedAdmissionStage.ROOT_ADMISSION })
    }

    @Test
    fun `unavailable owner observation rejects before current schema and root admission`() {
        val events = ArrayList<HostedAdmissionEvidence>()
        assertEquals(
            HostedServicesObservation.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE),
            scan(HostedEndpointOwnerObservation.UNAVAILABLE, events),
        )
        assertEquals(
            HostedAdmissionEvidence.Rejected(HostedAdmissionStage.OWNER_LIVENESS, ExistingIdeFailure.HOST_UNAVAILABLE),
            events.last(),
        )
        assertTrue(events.none { it.stage() == HostedAdmissionStage.CURRENT_DESCRIPTOR })
    }

    @Test
    fun `malformed eligible record rejects before process observation`() {
        val home = temporary.toRealPath()
        val entry = entry(home)
        Files.writeString(entry.resolve("endpoint.json"), "{malformed")
        val result =
            observeRunningHostedServices(
                home,
                HostedEndpointOwnerProbe { error("unexpected process observation") },
                HostedAdmissionObserver {},
            )
        assertEquals(HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED), result)
    }

    @Test
    fun `descriptor symlink cannot authorize process observation or alter protected target`() {
        val home = temporary.toRealPath()
        val entry = entry(home)
        val target = Files.write(home.resolve("protected.json"), bytes())
        Files.createSymbolicLink(entry.resolve("endpoint.json"), target)
        val result =
            observeRunningHostedServices(
                home,
                HostedEndpointOwnerProbe { error("unexpected process observation") },
                HostedAdmissionObserver {},
            )
        assertEquals(HostedServicesObservation.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED), result)
        assertEquals(bytes().decodeToString(), Files.readString(target))
    }

    private fun scan(
        observation: HostedEndpointOwnerObservation,
        events: MutableList<HostedAdmissionEvidence>,
    ): HostedServicesObservation {
        val home = temporary.toRealPath()
        val entry = entry(home)
        Files.write(entry.resolve("endpoint.json"), bytes())
        var calls = 0
        val result =
            observeRunningHostedServices(
                home,
                HostedEndpointOwnerProbe { process ->
                    assertEquals(123L, process.value)
                    assertEquals(0, calls++, "excess process observation")
                    observation
                },
                events::add,
            )
        assertEquals(1, calls, "unconsumed process observation")
        return result
    }

    private fun entry(home: Path): Path =
        Files.createDirectories(home.resolve(".kast/ide-hosted/" + "0".repeat(32))).also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }

    private fun bytes(): ByteArray =
        json.encodeToString(serializer<RecordedEndpoint>(), RecordedEndpoint()).toByteArray()

    private fun HostedAdmissionEvidence.stage(): HostedAdmissionStage =
        when (this) {
            is HostedAdmissionEvidence.Excluded -> stage
            is HostedAdmissionEvidence.Selected -> stage
            is HostedAdmissionEvidence.Rejected -> stage
        }

    @Serializable
    private data class RecordedEndpoint(
        val type: String = "KAST_IDE_ENDPOINT",
        val protocol: Int = 3,
        val root: String = "/removed/project",
        val socket: String = "/removed/host.sock",
        val hostPid: Long = 123,
        val operations: List<String> =
            listOf(
                "DESCRIBE",
                "CLASS_LOOKUP",
                "SUPERTYPE_LOOKUP",
                "SYMBOL_DISCOVER",
                "SYMBOL_INSPECT",
                "RELATION_READ",
                "TRAVERSAL_RUN",
                "CHANGE_PREPARE",
                "CHANGE_PEEK",
                "CHANGE_APPLY",
                "CHANGE_ABORT",
                "CHANGE_REVERT",
                "SOURCE_READ",
                "QUERY_RUN",
            ),
        val host: String = "00000000-0000-0000-0000-000000000001",
        val querySchema: String = "kast.query.run.v2",
    )
}
