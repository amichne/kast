package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL

class NativeImageConfigurationTest {
    @Test
    fun nativeReflectConfigDoesNotRetainHopliteConfigurationFieldDecoderTypes() {
        val entriesByName = parseJsonArray(nativeConfigResource("reflect-config.json"))
            .map { entry -> entry.jsonObject }
            .associateBy { entry -> entry["name"]?.jsonPrimitive?.content }
        val retainedConfigDecoderTypes = entriesByName.keys.filterNotNull().filter { className ->
            className == HOPLITE_CONFIGURATION_FIELD_DECODER ||
                className.startsWith(CONFIGURATION_FIELD_PACKAGE)
        }.sorted()

        assertTrue(
            retainedConfigDecoderTypes.isEmpty(),
            "KastConfig must not require Hoplite data-class or ConfigurationField decoder reflection in native images:\n" +
                retainedConfigDecoderTypes.joinToString("\n"),
        )
    }

    @Test
    fun nativeResourceConfigIncludesPackagedResourcesAndDoesNotServiceLoadKastConfigDecoder() {
        val patterns = parseJsonObject(nativeConfigResource("resource-config.json"))
            .getValue("resources")
            .jsonObject
            .getValue("includes")
            .jsonArray
            .map { include -> include.jsonObject.getValue("pattern").jsonPrimitive.content }
        val expectedPatterns = listOf(
            "packaged-skill/.*",
            "packaged-copilot-extension/.*",
        )
        val missingPatterns = expectedPatterns.filterNot(patterns::contains)

        assertTrue(
            missingPatterns.isEmpty(),
            "Add these resource patterns to resource-config.json:\n" + missingPatterns.joinToString("\n"),
        )

        assertTrue(
            Regex.escape(HOPLITE_DECODER_SERVICE_PATH) !in patterns,
            "Do not include the Kast Hoplite decoder service resource in native images; " +
                "KastConfig parses config without Hoplite data-class decoder binding.",
        )

        val serviceContents = NativeImageConfigurationTest::class.java.classLoader
            .getResources(HOPLITE_DECODER_SERVICE_PATH)
            .toList()
            .map { resource -> resource.readText() }
        assertTrue(
            serviceContents.none { contents ->
                contents.lineSequence().any { line -> line.trim() == HOPLITE_CONFIGURATION_FIELD_DECODER }
            },
            "Do not publish " + HOPLITE_CONFIGURATION_FIELD_DECODER + " through " + HOPLITE_DECODER_SERVICE_PATH +
                "; ServiceLoader instantiation is brittle in native images.",
        )
    }

    private fun parseJsonArray(resource: URL) = json.parseToJsonElement(resource.readText()).jsonArray

    private fun parseJsonObject(resource: URL) = json.parseToJsonElement(resource.readText()).jsonObject

    private fun nativeConfigResource(name: String): URL =
        checkNotNull(NativeImageConfigurationTest::class.java.classLoader.getResource(NATIVE_CONFIG_RESOURCE_ROOT + name)) {
            "Package native-image resource " + NATIVE_CONFIG_RESOURCE_ROOT + name
        }

    private fun URL.readText(): String = openStream().bufferedReader().use { reader -> reader.readText() }

    private companion object {
        const val CONFIGURATION_FIELD_PACKAGE = "io.github.amichne.kast.api.client.fields."
        const val HOPLITE_CONFIGURATION_FIELD_DECODER =
            "io.github.amichne.kast.api.client.ConfigurationFieldDecoder"
        const val HOPLITE_DECODER_SERVICE_PATH = "META-INF/services/com.sksamuel.hoplite.decoder.Decoder"
        const val NATIVE_CONFIG_RESOURCE_ROOT = "META-INF/native-image/io.github.amichne.kast/kast-cli/"
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}
