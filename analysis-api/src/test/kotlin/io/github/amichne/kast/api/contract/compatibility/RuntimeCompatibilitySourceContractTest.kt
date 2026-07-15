package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RuntimeCompatibilitySourceContractTest {
    @Test
    fun `authored matrix matches the typed revision and capability vocabulary`() {
        val sourcePath = Path.of(
            requireNotNull(System.getProperty(SOURCE_PATH_PROPERTY)) {
                "Missing runtime compatibility source test input"
            },
        )
        val source = Json.parseToJsonElement(Files.readString(sourcePath)).jsonObject
        val pairs = source.getValue("supportedPairs").jsonArray

        assertTrue(pairs.isNotEmpty(), "The compatibility source must name a supported row")
        val sameReleaseIdea = pairs
            .map { element -> element.jsonObject }
            .single { pair ->
                pair.string("relation") == "same-release" &&
                    pair.getValue("runtime").jsonObject.string("backendKind") == "IDEA" &&
                    pair.getValue("protocolRevision").jsonPrimitive.int == ProtocolRevision.CURRENT.value &&
                    pair.getValue("workspaceMetadataRevision").jsonPrimitive.int ==
                    WorkspaceMetadataRevision.CURRENT.value
            }
        assertEquals(
            ProtocolRevision.CURRENT.value,
            sameReleaseIdea.getValue("protocolRevision").jsonPrimitive.int,
        )
        assertEquals(
            WorkspaceMetadataRevision.CURRENT.value,
            sameReleaseIdea.getValue("workspaceMetadataRevision").jsonPrimitive.int,
        )
        assertEquals("{releaseVersion}", sameReleaseIdea.string("pluginVersion"))
        assertEquals("{releaseVersion}", sameReleaseIdea.string("cliVersion"))
        assertEquals(
            "{releaseVersion}",
            sameReleaseIdea.getValue("runtime").jsonObject.string("implementationVersion"),
        )

        val classifiedCapabilities = (
            sameReleaseIdea.getValue("requiredCapabilities").jsonArray +
                sameReleaseIdea.getValue("optionalCapabilities").jsonArray
            )
            .map { element ->
                val capability = element.jsonObject
                "${capability.string("kind")}:${capability.string("name")}"
            }
            .toSet()
        val typedCapabilities = buildSet {
            ReadCapability.entries.forEach { capability ->
                add("READ:${capability.name}")
            }
            MutationCapability.entries.forEach { capability ->
                add("MUTATION:${capability.name}")
            }
        }
        assertEquals(typedCapabilities, classifiedCapabilities)
    }

    @Test
    fun `every authored row has a typed matrix result and optional skew stays local`() {
        val source = readSource()
        val authoredRows = source.getValue("supportedPairs").jsonArray
            .map { element -> element.jsonObject.toSupportedPair() }
            .toSet()
        val matrix = RuntimeCompatibilityMatrix(authoredRows)

        authoredRows.forEach { row ->
            assertEquals(RuntimeCompatibilityOutcome.Compatible(row.facts), matrix.assess(row.facts))
        }

        val currentRow = authoredRows.single { row ->
            row.facts.pluginVersion == PluginImplementationVersion(TEST_RELEASE_VERSION) &&
                row.facts.cliVersion == CliImplementationVersion(TEST_RELEASE_VERSION) &&
                row.facts.protocolRevision == ProtocolRevision.CURRENT &&
                row.facts.workspaceMetadataRevision == WorkspaceMetadataRevision.CURRENT &&
                row.facts.runtimeIdentity.backendKind == RuntimeBackendKind.IDEA
        }
        val optionalCapability = readSource().getValue("supportedPairs").jsonArray
            .map { element -> element.jsonObject }
            .single { pair ->
                pair.string("relation") == "same-release" &&
                    pair.getValue("runtime").jsonObject.string("backendKind") == "IDEA" &&
                    pair.getValue("protocolRevision").jsonPrimitive.int == ProtocolRevision.CURRENT.value &&
                    pair.getValue("workspaceMetadataRevision").jsonPrimitive.int ==
                    WorkspaceMetadataRevision.CURRENT.value
            }
            .getValue("optionalCapabilities")
            .jsonArray
            .first()
            .jsonObject
            .toRuntimeCapability()
        val factsWithoutOptional = currentRow.facts.without(optionalCapability)

        assertEquals(
            RuntimeCompatibilityOutcome.MissingCapability(optionalCapability),
            matrix.assess(factsWithoutOptional, operationCapability = optionalCapability),
        )
        assertTrue(
            matrix.assess(
                currentRow.facts.copy(pluginVersion = PluginImplementationVersion("__unsupported__")),
            ) is RuntimeCompatibilityOutcome.UpdateRequired,
        )
    }

    private fun JsonObject.toSupportedPair(): SupportedRuntimeCompatibilityPair {
        val classified = (
            getValue("requiredCapabilities").jsonArray +
                getValue("optionalCapabilities").jsonArray
            )
            .map { element -> element.jsonObject.toRuntimeCapability() }
            .toSet()
        val runtime = getValue("runtime").jsonObject
        return SupportedRuntimeCompatibilityPair(
            facts = RuntimeCompatibilityFacts(
                pluginVersion = PluginImplementationVersion(resolveVersion(string("pluginVersion"))),
                cliVersion = CliImplementationVersion(resolveVersion(string("cliVersion"))),
                protocolRevision = ProtocolRevision(getValue("protocolRevision").jsonPrimitive.int),
                workspaceMetadataRevision = WorkspaceMetadataRevision(
                    getValue("workspaceMetadataRevision").jsonPrimitive.int,
                ),
                readCapabilities = classified.filterIsInstance<RuntimeCapability.Read>()
                    .mapTo(linkedSetOf()) { capability -> capability.capability },
                mutationCapabilities = classified.filterIsInstance<RuntimeCapability.Mutation>()
                    .mapTo(linkedSetOf()) { capability -> capability.capability },
                runtimeIdentity = RuntimeIdentity(
                    implementationVersion = RuntimeImplementationVersion(
                        resolveVersion(runtime.string("implementationVersion")),
                    ),
                    backendKind = RuntimeBackendKind.valueOf(runtime.string("backendKind")),
                ),
            ),
            requiredCapabilities = getValue("requiredCapabilities").jsonArray
                .mapTo(linkedSetOf()) { element -> element.jsonObject.toRuntimeCapability() },
        )
    }

    private fun JsonObject.toRuntimeCapability(): RuntimeCapability =
        when (string("kind")) {
            "READ" -> RuntimeCapability.Read(ReadCapability.valueOf(string("name")))
            "MUTATION" -> RuntimeCapability.Mutation(MutationCapability.valueOf(string("name")))
            else -> error("Unknown runtime capability kind: ${string("kind")}")
        }

    private fun RuntimeCompatibilityFacts.without(
        capability: RuntimeCapability,
    ): RuntimeCompatibilityFacts =
        when (capability) {
            is RuntimeCapability.Read -> copy(readCapabilities = readCapabilities - capability.capability)
            is RuntimeCapability.Mutation -> copy(
                mutationCapabilities = mutationCapabilities - capability.capability,
            )
        }

    private fun readSource(): JsonObject {
        val sourcePath = Path.of(
            requireNotNull(System.getProperty(SOURCE_PATH_PROPERTY)) {
                "Missing runtime compatibility source test input"
            },
        )
        return Json.parseToJsonElement(Files.readString(sourcePath)).jsonObject
    }

    private fun resolveVersion(value: String): String =
        if (value == RELEASE_VERSION_TEMPLATE) TEST_RELEASE_VERSION else value

    private fun JsonObject.string(field: String): String =
        getValue(field).jsonPrimitive.content

    private companion object {
        const val SOURCE_PATH_PROPERTY = "kast.runtimeCompatibilitySource"
        const val RELEASE_VERSION_TEMPLATE = "{releaseVersion}"
        const val TEST_RELEASE_VERSION = "0.13.0"
    }
}
