package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** Owner-envelope authority proves only a declared process identity, never a hosted contract. */
internal class RecordedHostedEndpointOwner
private constructor(
    val owner: HostedEndpointOwnerPid,
    private val raw: ByteArray,
) {
    fun endpoint(): Refinement<DeclaredHostedEndpoint, ExistingIdeFailure> = ExistingIdeDocuments.declaredEndpoint(raw)

    companion object {
        fun read(raw: ByteArray): Refinement<RecordedHostedEndpointOwner, ExistingIdeFailure> =
            when (val admitted = ExistingIdeDocuments.readSchema(raw, "/control/hosted-endpoint-owner.schema.json")) {
                is Refinement.Rejected ->
                    Refinement.Rejected(
                        if (admitted.failure == ExistingIdeFailure.SCHEMA_UNAVAILABLE) admitted.failure
                        else ExistingIdeFailure.DESCRIPTOR_REJECTED
                    )
                is Refinement.Refined -> {
                    val pid = admitted.value.path("hostPid")
                    if (!pid.isIntegralNumber) Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
                    else
                        when (val parsed = HostedEndpointOwnerPid.parse(pid.toString())) {
                            is Refinement.Rejected -> parsed
                            is Refinement.Refined ->
                                Refinement.Refined(RecordedHostedEndpointOwner(parsed.value, raw.copyOf()))
                        }
                }
            }
    }
}

/** Schema-admitted routing and owner facts; physical root and exact socket admission remain separate. */
internal class DeclaredHostedEndpoint
private constructor(
    val root: Path,
    val socket: Path,
    val owner: HostedEndpointOwnerPid,
    private val host: java.util.UUID,
    private val application: io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement?,
) {
    fun bind(root: CanonicalRoot, socket: Path): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> =
        if (this.root == root.path && this.socket == socket)
            Refinement.Refined(ExistingIdeDescriptor(owner, host, application))
        else Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)

    companion object {
        fun read(raw: ByteArray): Refinement<DeclaredHostedEndpoint, ExistingIdeFailure> =
            when (val read = ExistingIdeDocuments.readSchema(raw, "/ide-hosted/hosted-endpoint.schema.json")) {
                is Refinement.Rejected ->
                    Refinement.Rejected(
                        if (read.failure == ExistingIdeFailure.SCHEMA_UNAVAILABLE) read.failure
                        else ExistingIdeFailure.DESCRIPTOR_REJECTED
                    )
                is Refinement.Refined -> admit(read.value)
            }

        private fun admit(
            node: tools.jackson.databind.JsonNode
        ): Refinement<DeclaredHostedEndpoint, ExistingIdeFailure> {
            val rejected = Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            if (
                node.path("type").asString() != "KAST_IDE_ENDPOINT" ||
                    node.path("protocol").asInt() != HOSTED_PROTOCOL_VERSION
            )
                return rejected
            return try {
                val document =
                    kotlinx.serialization.json.Json.decodeFromString<EndpointRoutingDocument>(node.toString())
                val owner =
                    when (val parsed = HostedEndpointOwnerPid.parse(document.hostPid.toString())) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return parsed
                    }
                val rootText = document.root
                val socketText = document.socket
                val root = Path.of(rootText)
                val socket = Path.of(socketText)
                if (root.toString() != rootText || socket.toString() != socketText) rejected
                else if (root.normalize() != root || socket.normalize() != socket) rejected
                else
                    Refinement.Refined(
                        DeclaredHostedEndpoint(
                            root,
                            socket,
                            owner,
                            java.util.UUID.fromString(document.host),
                            document.application,
                        )
                    )
            } catch (_: IllegalArgumentException) {
                rejected
            } catch (_: kotlinx.serialization.SerializationException) {
                rejected
            }
        }
    }
}

private const val HOSTED_PROTOCOL_VERSION = 3

@kotlinx.serialization.Serializable
private data class EndpointRoutingDocument(
    val type: String,
    val protocol: Int,
    val root: String,
    val socket: String,
    val hostPid: Long,
    val host: String,
    val querySchema: String,
    val operations: List<String>,
    val application: io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement? = null,
)
