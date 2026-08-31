package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.ProductInspection
import io.github.amichne.kast.cli.ProductWorkspaceObservation
import io.github.amichne.kast.cli.RootSidecarCacheObservation
import io.github.amichne.kast.cli.SidecarProductIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object ProductInspectionDocuments {
    fun complete(inspection: ProductInspection): CliJsonDocument = productInspectionFactory.create(
        ProductInspectionDocument(
            operation = "product.inspect",
            status = "complete",
            control = inspection.control.outputDocument(),
            workspace = inspection.workspace.outputDocument(),
        ),
    )
}

@Serializable
private data class ProductInspectionDocument(
    val operation: String,
    val status: String,
    val control: SidecarProductDocument,
    val workspace: ProductWorkspaceDocument,
)

@Serializable
private data class SidecarProductDocument(
    val execution: String,
    val productVersion: String,
    val runtimeId: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val payloadDigest: String,
)

@Serializable
private sealed interface ProductWorkspaceDocument {
    @Serializable
    @SerialName("root-rejected")
    data class RootRejected(
        val failure: String,
    ) : ProductWorkspaceDocument

    @Serializable
    @SerialName("observed")
    data class Observed(
        val canonicalRoot: String,
        val cache: ProductCacheDocument,
    ) : ProductWorkspaceDocument
}

@Serializable
private sealed interface ProductCacheDocument {
    @Serializable
    @SerialName("absent")
    data class Absent(val state: String = "absent") : ProductCacheDocument

    @Serializable
    @SerialName("observed")
    data class Observed(
        val state: String,
        val identity: String,
        val ideaHome: String,
        val ideaBuild: String,
        val kotlinPluginBuild: String,
        val jbrIdentity: String,
        val payloadDigest: String,
    ) : ProductCacheDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: String) : ProductCacheDocument
}

private fun ProductWorkspaceObservation.outputDocument(): ProductWorkspaceDocument = when (this) {
    is ProductWorkspaceObservation.RootRejected -> ProductWorkspaceDocument.RootRejected(
        failure.outputName(),
    )
    is ProductWorkspaceObservation.Observed -> ProductWorkspaceDocument.Observed(
        root.path.toString(),
        cache.outputDocument(),
    )
}

private fun RootSidecarCacheObservation.outputDocument(): ProductCacheDocument = when (this) {
    RootSidecarCacheObservation.Absent -> ProductCacheDocument.Absent()
    is RootSidecarCacheObservation.Observed -> ProductCacheDocument.Observed(
        state = status.state.wireName,
        identity = status.cacheIdentity,
        ideaHome = status.ideaHome.toString(),
        ideaBuild = status.ideaBuild,
        kotlinPluginBuild = status.kotlinPluginBuild,
        jbrIdentity = status.jbrIdentity,
        payloadDigest = status.kastPayloadDigest,
    )
    is RootSidecarCacheObservation.Rejected -> ProductCacheDocument.Rejected(
        failure.outputName(),
    )
}

private fun SidecarProductIdentity.outputDocument(): SidecarProductDocument =
    SidecarProductDocument(
        execution = "isolated-intellij-sidecar",
        productVersion = productVersion.value,
        runtimeId = runtimeId.value,
        ideaBuild = supportedRuntime.ideaBuild,
        kotlinPluginBuild = supportedRuntime.kotlinPluginBuild,
        payloadDigest = payloadDigest.value,
    )

private fun Enum<*>.outputName(): String = name.lowercase().replace('_', '-')

private val productInspectionFactory =
    CliJsonDocument.generated(ProductInspectionDocument.serializer())
