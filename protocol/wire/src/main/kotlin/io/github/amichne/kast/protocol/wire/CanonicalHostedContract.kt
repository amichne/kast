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
            "query-presentation-header.schema.json",
        )
    val document: HostedContractDocument by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        schemaResources.forEach { name ->
            val resource =
                checkNotNull(javaClass.getResourceAsStream("/ide-hosted/$name")) { "Missing hosted schema: $name" }
            resource.use { update(digest, name, it.readBytes()) }
        }
        // The public lifecycle projection is control-owned; its actual host exchange is collected below.
        val roots =
            bindings
                .filter {
                    it.operation != io.github.amichne.kast.protocol.contract.CanonicalOperation.WORKSPACE_LIFECYCLE
                }
                .flatMap { it.contractDescriptors } +
                listOf(
                    io.github.amichne.kast.protocol.contract.IdeLifecycleCommand.serializer().descriptor,
                    io.github.amichne.kast.protocol.contract.IdeLifecycleResult.serializer().descriptor,
                    WireEnvelopeDocument.serializer().descriptor,
                )
        val shape = hostedWireShape(roots)
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

internal fun hostedWireShape(roots: List<SerialDescriptor>): HostedWireShapeDocument {
    val descriptors = LinkedHashMap<String, HostedWireDescriptor>()
    fun collect(node: HostedWireDescriptor) {
        if (descriptors.putIfAbsent(node.identity, node) != null) return
        repeat(node.descriptor.elementsCount) { collect(node.child(it)) }
    }
    roots.forEach { collect(HostedWireDescriptor.Declaration(it)) }
    return HostedWireShapeDocument(
        descriptors.entries
            .sortedBy { it.key }
            .map { (name, node) ->
                val descriptor = node.descriptor
                HostedWireTypeDocument(
                    name,
                    descriptor.kind.toString(),
                    descriptor.isNullable,
                    (0 until descriptor.elementsCount).map { index ->
                        val child = node.child(index)
                        HostedWireFieldDocument(
                            descriptor.getElementName(index),
                            child.identity,
                            descriptor.isElementOptional(index),
                            child.descriptor.isNullable,
                        )
                    },
                )
            }
    )
}

/** A polymorphic tag belongs to its sealed owner; equal tags in other owners identify different wire types. */
private sealed interface HostedWireDescriptor {
    val descriptor: SerialDescriptor
    val identity: String

    data class Declaration(override val descriptor: SerialDescriptor) : HostedWireDescriptor {
        override val identity = descriptor.declaredIdentity()
    }

    data class Cases(val owner: String, override val descriptor: SerialDescriptor) : HostedWireDescriptor {
        override val identity = "$owner#${descriptor.serialName}"
    }

    data class Case(val owner: String, override val descriptor: SerialDescriptor) : HostedWireDescriptor {
        override val identity = "$owner#${descriptor.serialName}"
    }

    fun child(index: Int): HostedWireDescriptor =
        when (this) {
            is Cases -> Case(owner, descriptor.getElementDescriptor(index))
            is Declaration,
            is Case ->
                if (descriptor.kind == kotlinx.serialization.descriptors.PolymorphicKind.SEALED && index == 1)
                    Cases(identity, descriptor.getElementDescriptor(index))
                else Declaration(descriptor.getElementDescriptor(index))
        }
}

private fun SerialDescriptor.declaredIdentity(): String =
    when (kind) {
        kotlinx.serialization.descriptors.StructureKind.LIST,
        kotlinx.serialization.descriptors.StructureKind.MAP ->
            serialName +
                (0 until elementsCount).joinToString(prefix = "<", postfix = ">") {
                    getElementDescriptor(it).declaredIdentity()
                }
        else -> serialName
    }

@Serializable internal data class HostedWireShapeDocument(val types: List<HostedWireTypeDocument>)

@Serializable
internal data class HostedWireTypeDocument(
    val name: String,
    val kind: String,
    val nullable: Boolean,
    val fields: List<HostedWireFieldDocument>,
)

@Serializable
internal data class HostedWireFieldDocument(
    val name: String,
    val valueType: String,
    val optional: Boolean,
    val nullable: Boolean,
)
