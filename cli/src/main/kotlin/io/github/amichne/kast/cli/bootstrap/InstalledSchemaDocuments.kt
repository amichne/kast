package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandSurface
import kotlinx.serialization.Serializable

internal enum class InstalledSchemaResource {
    OPERATION_REGISTRY,
    WIRE_SCHEMA,
    SEMANTIC_RUNTIME,
}

internal data class InstalledSchemaFailure(
    val resource: InstalledSchemaResource,
    val failure: CliOpenJsonObjectFailure,
)

internal sealed interface InstalledSchemaConstruction {
    data class Constructed(val document: CliJsonDocument) : InstalledSchemaConstruction
    data class Rejected(val failure: InstalledSchemaFailure) : InstalledSchemaConstruction
}

/**
 * Proof transition: `installed resource JSON + CliCommandSurface -> InstalledSchemaConstruction`.
 *
 * Establishes object-shaped open resource components embedded in one closed generated outer
 * schema document. [InstalledSchemaFailure] retains the exact resource and closed object-admission
 * failure. Raw resource text and the encoded document may leave only at metadata composition.
 */
internal fun installedSchema(
    operationRegistry: String,
    wireSchema: String,
    runtimeManifest: String,
    commandSurface: CliCommandSurface,
): InstalledSchemaConstruction {
    val operationRegistryObject = when (val admission = CliOpenJsonObject.parse(operationRegistry)) {
        is CliOpenJsonObjectAdmission.Admitted -> admission.value
        is CliOpenJsonObjectAdmission.Rejected -> return InstalledSchemaConstruction.Rejected(
            InstalledSchemaFailure(InstalledSchemaResource.OPERATION_REGISTRY, admission.failure),
        )
    }
    val wireSchemaObject = when (val admission = CliOpenJsonObject.parse(wireSchema)) {
        is CliOpenJsonObjectAdmission.Admitted -> admission.value
        is CliOpenJsonObjectAdmission.Rejected -> return InstalledSchemaConstruction.Rejected(
            InstalledSchemaFailure(InstalledSchemaResource.WIRE_SCHEMA, admission.failure),
        )
    }
    val runtimeManifestObject = when (val admission = CliOpenJsonObject.parse(runtimeManifest)) {
        is CliOpenJsonObjectAdmission.Admitted -> admission.value
        is CliOpenJsonObjectAdmission.Rejected -> return InstalledSchemaConstruction.Rejected(
            InstalledSchemaFailure(InstalledSchemaResource.SEMANTIC_RUNTIME, admission.failure),
        )
    }

    return InstalledSchemaConstruction.Constructed(
        installedSchemaFactory.create(
            InstalledSchemaDocument(
                schemaVersion = 1,
                operationRegistry = operationRegistryObject,
                wireSchema = wireSchemaObject,
                cliProjection = InstalledCliProjectionDocument(
                    localFlags = commandSurface.localFlags,
                    lifecycleCommands = commandSurface.lifecycleCommands.map { it.command },
                    commands = commandSurface.semanticCommands.map { it.usage },
                ),
                semanticRuntime = runtimeManifestObject,
            ),
        ),
    )
}

@Serializable
private data class InstalledSchemaDocument(
    val schemaVersion: Int,
    val operationRegistry: CliOpenJsonObject,
    val wireSchema: CliOpenJsonObject,
    val cliProjection: InstalledCliProjectionDocument,
    val semanticRuntime: CliOpenJsonObject,
)

@Serializable
private data class InstalledCliProjectionDocument(
    val localFlags: List<String>,
    val lifecycleCommands: List<String>,
    val commands: List<String>,
)

private val installedSchemaFactory = CliJsonDocument.generated(InstalledSchemaDocument.serializer())
