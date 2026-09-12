package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductInspectionCommandTest {
    private val version = (KastPluginVersion.parse("1.2.3") as Refinement.Refined).value

    private fun cli(): KastCli {
        val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created
        val metadata =
            CliLocalMetadata.admit("1.2.3", Json.encodeToString(SchemaFixture(1))) as CliLocalMetadataAdmission.Admitted
        return KastCli(
            graph.factory,
            CanonicalRootDiscoverer { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
            metadata.metadata,
            version,
        )
    }

    @Test
    fun `product inspection identifies existing IDE authority without inventing a worker`() {
        val result = cli().execute(emptyList(), Path.of("/missing")) as CliExit.Complete
        val document = Json.parseToJsonElement(result.document.value).jsonObject
        assertEquals(setOf("operation", "productVersion", "semanticAuthority", "workspace"), document.keys)
        assertEquals(JsonPrimitive("existing_ide"), document["semanticAuthority"])
        assertEquals(JsonPrimitive("rejected"), document.getValue("workspace").jsonObject["type"])
    }

    @Test
    fun `retired process commands fail without launching or enrolling a workspace`() {
        for (command in listOf("start", "stop")) {
            val result = cli().execute(listOf(command), Path.of("/missing")) as CliExit.BoundaryRejected
            assertEquals(CliBoundaryExitStatus.RUNTIME, result.status)
            assertEquals(
                JsonPrimitive("isolated-runtime-retired-use-ide-status"),
                Json.parseToJsonElement(result.document.value).jsonObject["reason"],
            )
        }
        assertTrue(cli().execute(listOf("start", "--cache", "seed"), Path.of("/missing")) is CliExit.BoundaryRejected)
    }

    @Serializable private data class SchemaFixture(val schemaVersion: Int)
}
