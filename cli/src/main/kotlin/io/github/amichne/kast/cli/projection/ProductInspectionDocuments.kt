package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CanonicalRootFailure
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.protocol.contract.KastPluginVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object ProductInspectionDocuments {
    fun complete(version: KastPluginVersion, workspace: CanonicalRootDiscovery): CliJsonDocument =
        factory.create(
            ProductInspectionDocument(
                "product.inspect",
                version.value,
                "existing_ide",
                when (workspace) {
                    is CanonicalRootDiscovery.Discovered -> WorkspaceDocument.Resolved(workspace.root.path.toString())
                    is CanonicalRootDiscovery.Rejected -> WorkspaceDocument.Rejected(workspace.failure)
                },
            )
        )
}

@Serializable
private data class ProductInspectionDocument(
    val operation: String,
    val productVersion: String,
    val semanticAuthority: String,
    val workspace: WorkspaceDocument,
)

@Serializable
private sealed interface WorkspaceDocument {
    @Serializable @SerialName("resolved") data class Resolved(val root: String) : WorkspaceDocument

    @Serializable @SerialName("rejected") data class Rejected(val failure: CanonicalRootFailure) : WorkspaceDocument
}

private val factory = CliJsonDocument.generated(ProductInspectionDocument.serializer())
