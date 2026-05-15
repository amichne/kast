package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.fields.ConfigurationField
import io.github.amichne.kast.api.client.ConfigurationFieldDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL

class NativeImageConfigurationTest {
    @Test
    fun nativeReflectConfigRetainsConfigurationFieldDecoderAndSubclasses() {
        val entriesByName = parseJsonArray(nativeConfigResource("reflect-config.json"))
            .map { entry -> entry.jsonObject }
            .associateBy { entry -> entry["name"]?.jsonPrimitive?.content }
        val expectedConfigurationTypes = ConfigurationField::class.sealedSubclasses
            .mapNotNull { subclass -> subclass.qualifiedName }
            .sorted()
        val expectedTypes = expectedConfigurationTypes + checkNotNull(ConfigurationFieldDecoder::class.qualifiedName)
        val missingTypes = expectedTypes.filterNot(entriesByName::containsKey)

        assertTrue(
            missingTypes.isEmpty(),
            "Add these ConfigurationField native reflection types to reflect-config.json:\n" +
            missingTypes.joinToString("\n"),
        )

        val missingConstructorAccess = expectedConfigurationTypes.filterNot { typeName ->
            val entry = entriesByName.getValue(typeName)
            entry.enabled("allPublicConstructors") || entry.enabled("allDeclaredConstructors")
        }
        assertTrue(
            missingConstructorAccess.isEmpty(),
            "Enable constructor reflection for ConfigurationField subclasses:\n" +
            missingConstructorAccess.joinToString("\n"),
        )
    }

    @Test
    fun nativeResourceConfigIncludesPackagedResourcesAndHopliteDecoderService() {
        val patterns = parseJsonObject(nativeConfigResource("resource-config.json"))
            .getValue("resources")
            .jsonObject
            .getValue("includes")
            .jsonArray
            .map { include -> include.jsonObject.getValue("pattern").jsonPrimitive.content }
        val expectedPatterns = listOf(
            "packaged-skill/.*",
            Regex.escape(HOPLITE_DECODER_SERVICE_PATH),
        )
        val missingPatterns = expectedPatterns.filterNot(patterns::contains)

        assertTrue(
            missingPatterns.isEmpty(),
            "Add these resource patterns to resource-config.json:\n" + missingPatterns.joinToString("\n"),
        )

        val serviceContents = ConfigurationFieldDecoder::class.java.classLoader
            .getResources(HOPLITE_DECODER_SERVICE_PATH)
            .toList()
            .map { resource -> resource.readText() }
        val decoderClassName = checkNotNull(ConfigurationFieldDecoder::class.qualifiedName)
        assertTrue(
            serviceContents.any { contents ->
                contents.lineSequence().any { line -> line.trim() == decoderClassName }
            },
            "Package " + HOPLITE_DECODER_SERVICE_PATH + " with " + decoderClassName,
        )
    }

    private fun parseJsonArray(resource: URL) = json.parseToJsonElement(resource.readText()).jsonArray

    private fun parseJsonObject(resource: URL) = json.parseToJsonElement(resource.readText()).jsonObject

    private fun JsonObject.enabled(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true

    private fun nativeConfigResource(name: String): URL =
        checkNotNull(ConfigurationFieldDecoder::class.java.classLoader.getResource(NATIVE_CONFIG_RESOURCE_ROOT + name)) {
            "Package native-image resource " + NATIVE_CONFIG_RESOURCE_ROOT + name
        }

    private fun URL.readText(): String = openStream().bufferedReader().use { reader -> reader.readText() }

    private companion object {
        const val HOPLITE_DECODER_SERVICE_PATH = "META-INF/services/com.sksamuel.hoplite.decoder.Decoder"
        const val NATIVE_CONFIG_RESOURCE_ROOT = "META-INF/native-image/io.github.amichne.kast/kast-cli/"
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}
