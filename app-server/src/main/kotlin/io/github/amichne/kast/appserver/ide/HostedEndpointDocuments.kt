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
) {
    fun bind(root: CanonicalRoot, socket: Path): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> =
        if (this.root == root.path && this.socket == socket) Refinement.Refined(ExistingIdeDescriptor(owner, host))
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
            val owner =
                when (val parsed = HostedEndpointOwnerPid.parse(node.path("hostPid").toString())) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            return try {
                val rootText = node.path("root").asString()
                val socketText = node.path("socket").asString()
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
                            java.util.UUID.fromString(node.path("host").asString()),
                        )
                    )
            } catch (_: IllegalArgumentException) {
                rejected
            }
        }
    }
}

private const val HOSTED_PROTOCOL_VERSION = 3
