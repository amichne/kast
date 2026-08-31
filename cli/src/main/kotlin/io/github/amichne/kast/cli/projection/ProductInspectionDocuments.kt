package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.IdeEndpointAdmissionFailure
import io.github.amichne.kast.cli.ProductEndpointObservation
import io.github.amichne.kast.cli.ProductInspection
import io.github.amichne.kast.cli.ProductWorkspaceObservation
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
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
    val control: ProductCompatibilityDocument,
    val workspace: ProductWorkspaceDocument,
)

@Serializable
private data class ProductCompatibilityDocument(
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
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
        val endpoint: ProductEndpointDocument,
    ) : ProductWorkspaceDocument
}

@Serializable
private sealed interface ProductEndpointDocument {
    @Serializable
    @SerialName("ready")
    data class Ready(
        val processId: Long,
        val runtimeEpoch: Long,
        val socketPath: String,
        val compatibility: ProductCompatibilityDocument,
    ) : ProductEndpointDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val failure: ProductEndpointFailureDocument,
    ) : ProductEndpointDocument
}

@Serializable
private sealed interface ProductEndpointFailureDocument {
    @Serializable
    @SerialName("invalid-root")
    data class InvalidRoot(val failure: String) : ProductEndpointFailureDocument

    @Serializable
    @SerialName("location-rejected")
    data class LocationRejected(val failure: String) : ProductEndpointFailureDocument

    @Serializable
    @SerialName("descriptor-read-rejected")
    data class DescriptorReadRejected(val failure: String) : ProductEndpointFailureDocument

    @Serializable
    @SerialName("descriptor-rejected")
    data class DescriptorRejected(
        val failure: CliIdeDescriptorFailureDocument,
    ) : ProductEndpointFailureDocument

    @Serializable
    @SerialName("root-mismatch")
    data object RootMismatch : ProductEndpointFailureDocument

    @Serializable
    @SerialName("socket-mismatch")
    data object SocketMismatch : ProductEndpointFailureDocument

    @Serializable
    @SerialName("process-unavailable")
    data object ProcessUnavailable : ProductEndpointFailureDocument

    @Serializable
    @SerialName("process-observation-rejected")
    data object ProcessObservationRejected : ProductEndpointFailureDocument

    @Serializable
    @SerialName("endpoint-unreachable")
    data object EndpointUnreachable : ProductEndpointFailureDocument
}

private fun ProductWorkspaceObservation.outputDocument(): ProductWorkspaceDocument = when (this) {
    is ProductWorkspaceObservation.RootRejected -> ProductWorkspaceDocument.RootRejected(
        failure.outputName(),
    )
    is ProductWorkspaceObservation.Observed -> ProductWorkspaceDocument.Observed(
        root.path.toString(),
        endpoint.outputDocument(),
    )
}

private fun ProductEndpointObservation.outputDocument(): ProductEndpointDocument = when (this) {
    is ProductEndpointObservation.Ready -> ProductEndpointDocument.Ready(
        processId = endpoint.descriptor.processId.value,
        runtimeEpoch = endpoint.descriptor.runtimeEpoch.value,
        socketPath = endpoint.descriptor.socketPath.value,
        compatibility = endpoint.descriptor.compatibility.outputDocument(),
    )
    is ProductEndpointObservation.Rejected -> ProductEndpointDocument.Rejected(
        failure.outputDocument(),
    )
}

private fun IdeEndpointAdmissionFailure.outputDocument(): ProductEndpointFailureDocument =
    when (this) {
        is IdeEndpointAdmissionFailure.InvalidRoot ->
            ProductEndpointFailureDocument.InvalidRoot(failure.outputName())
        is IdeEndpointAdmissionFailure.LocationRejected ->
            ProductEndpointFailureDocument.LocationRejected(failure.outputName())
        is IdeEndpointAdmissionFailure.DescriptorReadRejected ->
            ProductEndpointFailureDocument.DescriptorReadRejected(failure.outputName())
        is IdeEndpointAdmissionFailure.DescriptorRejected ->
            ProductEndpointFailureDocument.DescriptorRejected(failure.outputDetails())
        IdeEndpointAdmissionFailure.RootMismatch -> ProductEndpointFailureDocument.RootMismatch
        IdeEndpointAdmissionFailure.SocketMismatch -> ProductEndpointFailureDocument.SocketMismatch
        IdeEndpointAdmissionFailure.ProcessUnavailable ->
            ProductEndpointFailureDocument.ProcessUnavailable
        IdeEndpointAdmissionFailure.ProcessObservationRejected ->
            ProductEndpointFailureDocument.ProcessObservationRejected
        IdeEndpointAdmissionFailure.EndpointUnreachable ->
            ProductEndpointFailureDocument.EndpointUnreachable
    }

private fun AdmittedIdeHostCompatibility.outputDocument(): ProductCompatibilityDocument =
    ProductCompatibilityDocument(
        ideBuild = ideBuild.value,
        kotlinPluginBuild = kotlinPluginBuild.value,
        kastPluginVersion = kastPluginVersion.value,
        runtimeProtocolIdentity = runtimeProtocolIdentity.value,
        operationRegistryDigest = operationRegistryDigest.value,
        wireSchemaDigest = wireSchemaDigest.value,
        capabilities = capabilities.capabilities.map { it.operation.id.value },
    )

private fun Enum<*>.outputName(): String = name.lowercase().replace('_', '-')

private val productInspectionFactory =
    CliJsonDocument.generated(ProductInspectionDocument.serializer())
