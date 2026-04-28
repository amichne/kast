package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.wrapper.KastCallersRequest
import io.github.amichne.kast.api.wrapper.KastDiagnosticsRequest
import io.github.amichne.kast.api.wrapper.KastMetricsRequest
import io.github.amichne.kast.api.wrapper.KastReferencesRequest
import io.github.amichne.kast.api.wrapper.KastRenameByOffsetRequest
import io.github.amichne.kast.api.wrapper.KastRenameBySymbolRequest
import io.github.amichne.kast.api.wrapper.KastRenameRequest
import io.github.amichne.kast.api.wrapper.KastResolveRequest
import io.github.amichne.kast.api.wrapper.KastScaffoldRequest
import io.github.amichne.kast.api.wrapper.KastWorkspaceFilesRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateCreateFileRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateInsertAtOffsetRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateReplaceRangeRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateRequest
import io.github.amichne.kast.cli.CliCommandCatalog
import io.github.amichne.kast.cli.CliCommandMetadata
import io.github.amichne.kast.cli.defaultCliJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class SkillWrapperRequestCasingTest {
    private val json: Json = defaultCliJson()

    @Test
    fun `skill usage examples use valid serialized request keys`() {
        skillCommands().forEach { command ->
            val name = SkillWrapperName.fromCliName(command.path[1])
                ?: error("Unknown skill wrapper ${command.path[1]}")
            val usageKeys = command.usages
                .map(::extractJson)
                .flatMap { usage -> json.parseToJsonElement(usage).jsonObject.keys }
                .toSet()
            val validKeys = requestKeyMapping(name)
            val invalidKeys = usageKeys - validKeys
            assertTrue(
                invalidKeys.isEmpty(),
                "Invalid serialized request keys for ${command.commandText}: ${invalidKeys.joinToString()} not in ${validKeys.sorted()}",
            )
        }
    }

    /**
     * Round-trips every documented usage JSON through the actual request serializer used at
     * runtime. This catches stale `@SerialName` values on enums (e.g. `WrapperMetric.fanIn`)
     * and stale sealed-class discriminators (e.g. `RENAME_BY_SYMBOL_REQUEST`) that the
     * key-only check above does not validate.
     */
    @Test
    fun `skill usage examples deserialize cleanly into their request types`() {
        skillCommands().forEach { command ->
            val name = SkillWrapperName.fromCliName(command.path[1])
                ?: error("Unknown skill wrapper ${command.path[1]}")
            val serializer = rootRequestSerializer(name)
            command.usages.map(::extractJson).forEach { usage ->
                assertDoesNotThrow(
                    {
                        json.decodeFromString(serializer, usage)
                    },
                    "Usage example for ${command.commandText} failed to deserialize: $usage",
                )
            }
        }
    }

    private fun skillCommands(): List<CliCommandMetadata> {
        val field = CliCommandCatalog::class.java.getDeclaredField("commands")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val commands = field.get(CliCommandCatalog) as List<CliCommandMetadata>
        return commands.filter { it.path.firstOrNull() == "skill" }
    }

    private fun extractJson(usage: String): String = usage.substringAfter('\'').substringBeforeLast('\'')

    private fun rootRequestSerializer(name: SkillWrapperName): KSerializer<*> = when (name) {
        SkillWrapperName.RESOLVE -> KastResolveRequest.serializer()
        SkillWrapperName.REFERENCES -> KastReferencesRequest.serializer()
        SkillWrapperName.CALLERS -> KastCallersRequest.serializer()
        SkillWrapperName.DIAGNOSTICS -> KastDiagnosticsRequest.serializer()
        SkillWrapperName.RENAME -> KastRenameRequest.serializer()
        SkillWrapperName.SCAFFOLD -> KastScaffoldRequest.serializer()
        SkillWrapperName.WRITE_AND_VALIDATE -> KastWriteAndValidateRequest.serializer()
        SkillWrapperName.WORKSPACE_FILES -> KastWorkspaceFilesRequest.serializer()
        SkillWrapperName.METRICS -> KastMetricsRequest.serializer()
    }

    private fun requestKeyMapping(name: SkillWrapperName): Set<String> = when (name) {
        SkillWrapperName.RESOLVE -> serializedKeys(KastResolveRequest.serializer())
        SkillWrapperName.REFERENCES -> serializedKeys(KastReferencesRequest.serializer())
        SkillWrapperName.CALLERS -> serializedKeys(KastCallersRequest.serializer())
        SkillWrapperName.DIAGNOSTICS -> serializedKeys(KastDiagnosticsRequest.serializer())
        SkillWrapperName.RENAME -> serializedKeys(
            KastRenameRequest.serializer(),
            KastRenameBySymbolRequest.serializer(),
            KastRenameByOffsetRequest.serializer(),
        )
        SkillWrapperName.SCAFFOLD -> serializedKeys(KastScaffoldRequest.serializer())
        SkillWrapperName.WRITE_AND_VALIDATE -> serializedKeys(
            KastWriteAndValidateRequest.serializer(),
            KastWriteAndValidateCreateFileRequest.serializer(),
            KastWriteAndValidateInsertAtOffsetRequest.serializer(),
            KastWriteAndValidateReplaceRangeRequest.serializer(),
        )
        SkillWrapperName.WORKSPACE_FILES -> serializedKeys(KastWorkspaceFilesRequest.serializer())
        SkillWrapperName.METRICS -> serializedKeys(KastMetricsRequest.serializer())
    }

    private fun serializedKeys(
        root: KSerializer<*>,
        vararg concrete: KSerializer<*>,
    ): Set<String> = buildSet {
        addAll(descriptorKeys(root.descriptor))
        concrete.forEach { addAll(descriptorKeys(it.descriptor)) }
        if (root.descriptor.kind == PolymorphicKind.SEALED) {
            add("type")
        }
    }

    private fun descriptorKeys(descriptor: SerialDescriptor): Set<String> =
        (0 until descriptor.elementsCount)
            .map(descriptor::getElementName)
            .toSet()
}
