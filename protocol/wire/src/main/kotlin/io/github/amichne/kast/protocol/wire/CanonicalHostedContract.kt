package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedContract
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Shared boundary authority. Implementations must bump protocolIdentity when behavior breaks without a shape change.
 */
object CanonicalHostedContract {
    const val protocolIdentity = "kast.ide-hosted.runtime.v3"
    private val bindings =
        listOf(
            CanonicalOperationWireBindings.workspaceLifecycle,
            CanonicalOperationWireBindings.queryRun,
            CanonicalOperationWireBindings.sourceRead,
            CanonicalOperationWireBindings.diagnosticCheck,
            CanonicalOperationWireBindings.changePlan,
            CanonicalOperationWireBindings.changeApply,
            CanonicalOperationWireBindings.changeRecover,
        )
    private val schemaResources =
        listOf(
            "hosted-endpoint.schema.json",
            "hosted-query.schema.json",
            "hosted-workspace-refresh.schema.json",
            "hosted-request.schema.json",
        )
    val document: HostedContractDocument by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        schemaResources.forEach { name ->
            val resource =
                checkNotNull(javaClass.getResourceAsStream("/ide-hosted/$name")) { "Missing hosted schema: $name" }
            resource.use { update(digest, name, it.readBytes()) }
        }
        val descriptors = LinkedHashMap<String, SerialDescriptor>()
        fun identity(descriptor: SerialDescriptor): String =
            when (descriptor.kind) {
                kotlinx.serialization.descriptors.StructureKind.LIST,
                kotlinx.serialization.descriptors.StructureKind.MAP ->
                    descriptor.serialName +
                        (0 until descriptor.elementsCount).joinToString(prefix = "<", postfix = ">") {
                            identity(descriptor.getElementDescriptor(it))
                        }
                else -> descriptor.serialName
            }
        fun collect(descriptor: SerialDescriptor) {
            if (descriptors.putIfAbsent(identity(descriptor), descriptor) != null) return
            repeat(descriptor.elementsCount) { collect(descriptor.getElementDescriptor(it)) }
        }
        // The public lifecycle projection is control-owned; its actual host exchange is collected below.
        bindings
            .filter { it.operation != io.github.amichne.kast.protocol.contract.CanonicalOperation.WORKSPACE_LIFECYCLE }
            .flatMap { it.contractDescriptors }
            .forEach(::collect)
        // Application lifecycle uses its existing direct framed exchange rather than an operation envelope.
        collect(io.github.amichne.kast.protocol.contract.IdeLifecycleCommand.serializer().descriptor)
        collect(io.github.amichne.kast.protocol.contract.IdeLifecycleResult.serializer().descriptor)
        collect(WireEnvelopeDocument.serializer().descriptor)
        val shape =
            HostedWireShapeDocument(
                descriptors.entries
                    .sortedBy { it.key }
                    .map { (name, descriptor) ->
                        HostedWireTypeDocument(
                            name,
                            descriptor.kind.toString(),
                            descriptor.isNullable,
                            (0 until descriptor.elementsCount).map { index ->
                                HostedWireFieldDocument(
                                    descriptor.getElementName(index),
                                    identity(descriptor.getElementDescriptor(index)),
                                    descriptor.isElementOptional(index),
                                    descriptor.getElementDescriptor(index).isNullable,
                                )
                            },
                        )
                    }
            )
        update(
            digest,
            "generated-wire-shape",
            wireJson.encodeToString(HostedWireShapeDocument.serializer(), shape).toByteArray(),
        )
        val registry =
            checkNotNull(javaClass.getResourceAsStream("/ide-hosted/hosted-query.operations.json")).use {
                it.readBytes()
            }
        HostedContractDocument(
            runtimeProtocolIdentity = protocolIdentity,
            operationRegistryDigest = sha256(registry),
            wireSchemaDigest = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) },
            capabilities = bindings.map { it.operation.id.value },
        )
    }
    val required: HostedContract by lazy {
        when (val admitted = document.admit()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Invalid packaged hosted contract: ${admitted.failure}")
        }
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty())
        print(wireJson.encodeToString(HostedContractDocument.serializer(), document) + "\n")
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(digest: MessageDigest, name: String, bytes: ByteArray) {
        digest.update(name.toByteArray())
        digest.update(0.toByte())
        digest.update(java.nio.ByteBuffer.allocate(java.lang.Long.BYTES).putLong(bytes.size.toLong()).array())
        digest.update(bytes)
    }
}

@Serializable private data class HostedWireShapeDocument(val types: List<HostedWireTypeDocument>)

@Serializable
private data class HostedWireTypeDocument(
    val name: String,
    val kind: String,
    val nullable: Boolean,
    val fields: List<HostedWireFieldDocument>,
)

@Serializable
private data class HostedWireFieldDocument(
    val name: String,
    val valueType: String,
    val optional: Boolean,
    val nullable: Boolean,
)
