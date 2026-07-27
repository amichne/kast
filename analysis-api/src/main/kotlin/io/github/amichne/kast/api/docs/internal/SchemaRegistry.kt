package io.github.amichne.kast.api.docs.internal

import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.ReadCapability
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

@OptIn(ExperimentalSerializationApi::class)
internal class SchemaRegistry {
    val schemas = linkedMapOf<String, Any?>()
    private val registeredDescriptors = mutableMapOf<String, String>()

    fun register(
        name: String,
        serializer: KSerializer<*>,
    ) {
        if (schemas.containsKey(name)) return
        registeredDescriptors[serializer.descriptor.serialName] = name
        schemas[name] = emptyMap<String, Any?>()
        schemas[name] = schemaFor(serializer.descriptor, rootName = name)
    }

    fun registerSynthetic(
        name: String,
        serializer: KSerializer<*>,
    ) {
        if (schemas.containsKey(name)) return
        schemas[name] = schemaFor(serializer.descriptor, rootName = name)
    }

    private fun schemaFor(
        descriptor: SerialDescriptor,
        rootName: String? = null,
        includeNullable: Boolean = true,
    ): Map<String, Any?> {
        manualUnionSchema(rootName ?: simpleName(descriptor.serialName))?.let { return it }

        val schema = if (descriptor.isInline) {
            inlineValueSchema(descriptor, rootName ?: simpleName(descriptor.serialName))
        } else when (descriptor.kind) {
            is PrimitiveKind -> primitiveSchema(descriptor.kind as PrimitiveKind)
            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor)
            StructureKind.LIST -> collectionSchema(descriptor)
            StructureKind.MAP -> linkedMapOf(
                "type" to "object",
                "additionalProperties" to inlineSchema(descriptor.getElementDescriptor(1)),
            )
            SerialKind.ENUM -> linkedMapOf(
                "type" to "string",
                "enum" to enumValues(descriptor),
            )
            PolymorphicKind.SEALED -> linkedMapOf("type" to "object")
            else -> linkedMapOf("type" to "object")
        }
        return if (includeNullable && descriptor.isNullable) {
            linkedMapOf("anyOf" to listOf(schema, linkedMapOf("type" to "null")))
        } else {
            schema
        }
    }

    internal fun objectSchema(descriptor: SerialDescriptor): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            properties[name] = inlineSchema(descriptor.getElementDescriptor(index))
            if (!descriptor.isElementOptional(index)) {
                required += name
            }
        }
        return linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties,
            "additionalProperties" to false,
        ).also { if (required.isNotEmpty()) it["required"] = required }
    }

    private fun collectionSchema(descriptor: SerialDescriptor): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "type" to "array",
            "items" to inlineSchema(descriptor.getElementDescriptor(0)),
        ).also { schema ->
            if (descriptor.serialName.endsWith("HashSet") || descriptor.serialName.endsWith(".Set")) {
                schema["uniqueItems"] = true
            }
        }

    private fun inlineSchema(descriptor: SerialDescriptor): Any =
        when (descriptor.kind) {
            is PrimitiveKind -> schemaFor(descriptor)
            StructureKind.LIST -> schemaFor(descriptor)
            StructureKind.MAP -> schemaFor(descriptor)
            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                val refMap = linkedMapOf(
                    "\$ref" to "#/components/schemas/${ensureRegistered(descriptor, forceNonNullable = true)}",
                )
                if (descriptor.isNullable) {
                    linkedMapOf("anyOf" to listOf(refMap, linkedMapOf("type" to "null")))
                } else {
                    refMap
                }
            }
            else -> linkedMapOf("type" to "object")
        }

    private fun ensureRegistered(
        descriptor: SerialDescriptor,
        forceNonNullable: Boolean = false,
    ): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        registeredDescriptors[serialName]?.let { return it }
        val componentName = simpleName(serialName)
        if (!schemas.containsKey(componentName)) {
            registeredDescriptors[serialName] = componentName
            schemas[componentName] = emptyMap<String, Any?>()
            schemas[componentName] = schemaFor(
                descriptor = descriptor,
                rootName = componentName,
                includeNullable = !forceNonNullable,
            )
        }
        return componentName
    }

    private fun inlineValueSchema(
        descriptor: SerialDescriptor,
        componentName: String,
    ): Map<String, Any?> {
        val valueDescriptor = descriptor.getElementDescriptor(0)
        val schema = LinkedHashMap(primitiveSchema(valueDescriptor.kind as PrimitiveKind))
        when (componentName) {
            "ProtocolRevision", "WorkspaceMetadataRevision" -> schema["minimum"] = 1
            "PluginImplementationVersion", "CliImplementationVersion", "RuntimeImplementationVersion" -> {
                schema["minLength"] = 1
                schema["pattern"] = "^\\S+$"
            }
            "WorkspaceRoot", "BackendName", "NormalizedQuery", "Projection" -> schema["minLength"] = 1
            "Limit" -> {
                schema["minimum"] = 1
                schema["maximum"] = 200
            }
            "CompositionStampDigest" -> {
                schema["minLength"] = 64
                schema["maxLength"] = 64
                schema["pattern"] = "^[0-9a-f]{64}$"
            }
            "LastRelativePath" -> {
                schema["minLength"] = 1
                schema["pattern"] =
                    "^(?!/)(?![A-Za-z]:)(?![.]{1,2}(?:/|$))(?!.*(?:/)[.]{1,2}(?:/|$))" +
                    "(?!.*//)[^\\\\\\u0000-\\u001F\\u007F-\\u009F]+$"
            }
            "CumulativeReturnedCount" -> schema["minimum"] = 0
            "WorkspaceFilesPublicPageToken" -> {
                schema["format"] = "uuid"
                schema["minLength"] = 36
                schema["maxLength"] = 36
                schema["pattern"] =
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            }
        }
        return schema
    }

    internal fun refSchema(componentName: String): Map<String, Any?> =
        linkedMapOf("\$ref" to "#/components/schemas/$componentName")

    private fun primitiveSchema(kind: PrimitiveKind): Map<String, Any?> =
        when (kind) {
            PrimitiveKind.BOOLEAN -> linkedMapOf("type" to "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT ->
                linkedMapOf("type" to "integer", "format" to "int32")
            PrimitiveKind.LONG -> linkedMapOf("type" to "integer", "format" to "int64")
            PrimitiveKind.FLOAT -> linkedMapOf("type" to "number", "format" to "float")
            PrimitiveKind.DOUBLE -> linkedMapOf("type" to "number", "format" to "double")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> linkedMapOf("type" to "string")
        }

    private fun simpleName(serialName: String): String = serialName.substringAfterLast('.')

    private fun enumValues(descriptor: SerialDescriptor): List<String> {
        val values = List(descriptor.elementsCount) { descriptor.getElementName(it) }
        return if (descriptor.serialName.removeSuffix("?") == ReadCapability::class.qualifiedName) {
            values.filterNot { it == ReadCapability.WORKSPACE_SEARCH.name }
        } else {
            values
        }
    }
}
