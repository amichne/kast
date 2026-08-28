package support.delivery

import kotlinx.serialization.Serializable

@Serializable
internal data class Kvp024DescriptorBindingDocument(
    val field: Kvp024DescriptorField,
    val source: Kvp024DescriptorSource,
)

@Serializable
internal enum class Kvp024DescriptorField {
    SCHEMA,
    CANONICAL_ROOT,
    HOST_KIND,
    PROCESS_ID,
    IDE_BUILD,
    KOTLIN_PLUGIN_BUILD,
    KAST_PLUGIN_VERSION,
    RUNTIME_PROTOCOL_IDENTITY,
    OPERATION_REGISTRY_DIGEST,
    WIRE_SCHEMA_DIGEST,
    SOCKET_PATH,
    FRAMING,
    RUNTIME_EPOCH,
    CAPABILITIES,
}

@Serializable
internal enum class Kvp024DescriptorSource {
    SCHEMA_CONSTANT,
    ADMITTED_IDE_PROJECT,
    IDE_PROJECT_CONSTANT,
    CURRENT_PROCESS,
    ADMITTED_COMPATIBILITY,
    BOUND_SOCKET,
    FRAMING_CONSTANT,
    PROJECT_ENDPOINT_GENERATION,
}

internal fun canonicalKvp024DescriptorBindings() = listOf(
    binding(Kvp024DescriptorField.SCHEMA, Kvp024DescriptorSource.SCHEMA_CONSTANT),
    binding(Kvp024DescriptorField.CANONICAL_ROOT, Kvp024DescriptorSource.ADMITTED_IDE_PROJECT),
    binding(Kvp024DescriptorField.HOST_KIND, Kvp024DescriptorSource.IDE_PROJECT_CONSTANT),
    binding(Kvp024DescriptorField.PROCESS_ID, Kvp024DescriptorSource.CURRENT_PROCESS),
    binding(Kvp024DescriptorField.IDE_BUILD, Kvp024DescriptorSource.ADMITTED_COMPATIBILITY),
    binding(
        Kvp024DescriptorField.KOTLIN_PLUGIN_BUILD,
        Kvp024DescriptorSource.ADMITTED_COMPATIBILITY,
    ),
    binding(
        Kvp024DescriptorField.KAST_PLUGIN_VERSION,
        Kvp024DescriptorSource.ADMITTED_COMPATIBILITY,
    ),
    binding(
        Kvp024DescriptorField.RUNTIME_PROTOCOL_IDENTITY,
        Kvp024DescriptorSource.ADMITTED_COMPATIBILITY,
    ),
    binding(
        Kvp024DescriptorField.OPERATION_REGISTRY_DIGEST,
        Kvp024DescriptorSource.ADMITTED_COMPATIBILITY,
    ),
    binding(
        Kvp024DescriptorField.WIRE_SCHEMA_DIGEST,
        Kvp024DescriptorSource.ADMITTED_COMPATIBILITY,
    ),
    binding(Kvp024DescriptorField.SOCKET_PATH, Kvp024DescriptorSource.BOUND_SOCKET),
    binding(Kvp024DescriptorField.FRAMING, Kvp024DescriptorSource.FRAMING_CONSTANT),
    binding(
        Kvp024DescriptorField.RUNTIME_EPOCH,
        Kvp024DescriptorSource.PROJECT_ENDPOINT_GENERATION,
    ),
    binding(Kvp024DescriptorField.CAPABILITIES, Kvp024DescriptorSource.ADMITTED_COMPATIBILITY),
)

private fun binding(field: Kvp024DescriptorField, source: Kvp024DescriptorSource) =
    Kvp024DescriptorBindingDocument(field, source)
