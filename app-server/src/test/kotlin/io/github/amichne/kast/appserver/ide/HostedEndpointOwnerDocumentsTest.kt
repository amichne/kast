package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Owner-envelope and encoded evidence policy has no filesystem or process dependency. */
class HostedEndpointOwnerDocumentsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `owner envelope retains positive integral identity without admitting legacy compatibility`() {
        val read = assertInstanceOf(Refinement.Refined::class.java, ExistingIdeDocuments.recordedOwner(bytes()))
        val recorded = read.value as RecordedHostedEndpointOwner
        assertEquals(123L, recorded.owner.value)
        assertEquals(Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED), recorded.endpoint())
    }

    @Test
    fun `invalid numeric owner encodings duplicate fields and trailing documents reject`() {
        val valid = bytes().decodeToString()
        val invalid =
            listOf("0", "-1", "1.0", "1e0", "\"123\"", "9223372036854775808", "null").map {
                valid.replace("\"hostPid\":123", "\"hostPid\":$it")
            } +
                listOf(
                    valid.replace("\"hostPid\":123", "\"hostPid\":123,\"hostPid\":123"),
                    "$valid {}",
                    valid.dropLast(1) + ",\"unexpected\":true}",
                )
        for (raw in invalid) assertEquals(
            Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
            ExistingIdeDocuments.recordedOwner(raw.toByteArray()),
            raw,
        )
    }

    @Test
    fun `evidence has independent closed encoded shape and bounded repeated tuples`() {
        val events =
            listOf(
                HostedAdmissionEvidence.Excluded(HostedAdmissionStage.ENTRY_FAMILY),
                HostedAdmissionEvidence.Selected(HostedAdmissionStage.OWNER_LIVENESS),
                HostedAdmissionEvidence.Rejected(
                    HostedAdmissionStage.CURRENT_DESCRIPTOR,
                    ExistingIdeFailure.DESCRIPTOR_REJECTED,
                ),
            )
        val expected =
            javaClass.getResourceAsStream("/control/hosted-admission-evidence.json")!!.bufferedReader().use { reader ->
                Json.parseToJsonElement(reader.readText()).jsonArray.map { it.toString() }
            }
        assertEquals(expected, events.map { json.encodeToString(HostedAdmissionEvidence.serializer(), it) })
        val rejected = expected.last()
        for (unknown in
            listOf(
                rejected.replace("HOST_ADMISSION_REJECTED", "HOST_ADMISSION_UNKNOWN"),
                rejected.replace("CURRENT_DESCRIPTOR", "UNKNOWN_STAGE"),
                rejected.replace("DESCRIPTOR_REJECTED", "UNKNOWN_FAILURE"),
            )) assertThrows(SerializationException::class.java) {
            json.decodeFromString(HostedAdmissionEvidence.serializer(), unknown)
        }
        val emitted = ArrayList<HostedAdmissionEvidence>()
        val observer = BoundedHostedAdmissionObserver(emitted::add)
        repeat(1_000) { events.forEach(observer::record) }
        assertEquals(events, emitted)
    }

    private fun bytes(): ByteArray =
        json.encodeToString(serializer<RecordedEndpoint>(), RecordedEndpoint()).toByteArray()

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
