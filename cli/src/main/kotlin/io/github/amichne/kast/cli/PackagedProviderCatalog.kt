package io.github.amichne.kast.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Build-time projection of the same schemas exposed by the installed CLI. Never invokes a command. */
object PackagedProviderCatalog {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        print(Json.encodeToString(PackagedCatalogDocument.serializer(), document()))
    }

    internal fun document() =
        PackagedCatalogDocument(
            schemaVersion = 1,
            serverProjection =
                PackagedServerProjectionDocument(
                    SERVER_PROJECTION_SCHEMA_VERSION,
                    "kast",
                    installedHostedBootstrap(),
                ),
        )
}

@Serializable
internal data class PackagedCatalogDocument(
    val schemaVersion: Int,
    val serverProjection: PackagedServerProjectionDocument,
)

@Serializable
internal data class PackagedServerProjectionDocument(
    val schemaVersion: Int,
    val namespace: String,
    val hostedBootstrap: InstalledHostedBootstrapDocument,
)
