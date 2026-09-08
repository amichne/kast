package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.IdeHomeSelection
import io.github.amichne.kast.cli.IndexSeedFailure
import io.github.amichne.kast.cli.InstalledCompositionFailure
import io.github.amichne.kast.cli.InstalledIdeRuntimeDiscovery
import io.github.amichne.kast.cli.InstalledIdeRuntimeDiscoveryResult
import io.github.amichne.kast.cli.KastCliCompositionFailure
import io.github.amichne.kast.cli.ProductInspection
import io.github.amichne.kast.cli.ProductTelemetryObservation
import io.github.amichne.kast.cli.ProductWorkspaceObservation
import io.github.amichne.kast.cli.RootSidecarCacheObservation
import io.github.amichne.kast.cli.SidecarProductIdentity
import io.github.amichne.kast.distribution.managed.network.InstalledNetworkBootstrap
import io.github.amichne.kast.distribution.managed.network.NetworkPolicyObservation
import io.github.amichne.kast.distribution.managed.network.NetworkPolicyReceipt
import io.github.amichne.kast.distribution.managed.network.RecordedNetworkAuthority
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal object ProductInspectionDocuments {
    /** Both inputs are generated documents; this final serialization boundary combines passive evidence. */
    fun passive(inspection: ProductInspection, runtime: CliExit): CliJsonDocument {
        val product = Json.parseToJsonElement(complete(inspection).value).jsonObject
        val runtimeDocument = when (runtime) {
            is CliExit.Complete -> runtime.document
            is CliExit.Qualified -> runtime.document
            is CliExit.OperationRejected -> runtime.document
            is CliExit.BoundaryRejected -> runtime.document
            is CliExit.Delegated -> CliBoundaryDocuments.boundaryRejected(
                CliBoundaryExitStatus.BOOTSTRAP,
                "passive-runtime-delegated",
            )
        }
        val status = Json.parseToJsonElement(runtimeDocument.value).jsonObject
        return passiveFactory.create(kotlinx.serialization.json.buildJsonObject {
            status.forEach { (key, value) -> if (key != "command") put(key, value) }
            put("operation", JsonPrimitive("inspect"))
            put("status", JsonPrimitive("complete"))
            put("control", product.getValue("control"))
            put("ide", ideObservation(inspection.control))
            put("workspace", product.getValue("workspace"))
            when (val workspace = inspection.workspace) {
                is ProductWorkspaceObservation.Observed -> put("network", networkObservation(workspace))
                is ProductWorkspaceObservation.RootRejected -> Unit
            }
        })
    }

    fun blocked(start: Path, failure: KastCliCompositionFailure): CliJsonDocument =
        passiveFactory.create(kotlinx.serialization.json.buildJsonObject {
            put("operation", JsonPrimitive("inspect"))
            put("status", JsonPrimitive("complete"))
            put("runtime", JsonPrimitive("unobserved"))
            put("blockingReason", JsonPrimitive(compositionReason(failure)))
            when (val identity = io.github.amichne.kast.cli.inspectInstalledControlIdentity()) {
                is Refinement.Refined -> {
                    put("control", Json.encodeToJsonElement(SidecarProductDocument.serializer(), identity.value.outputDocument()))
                    put("ide", ideObservation(identity.value))
                }
                is Refinement.Rejected -> put("controlFailure", JsonPrimitive(compositionReason(identity.failure)))
            }
            when (val root = FilesystemCanonicalRootDiscovery.discover(start)) {
                is CanonicalRootDiscovery.Discovered -> put("root", JsonPrimitive(root.root.path.toString()))
                is CanonicalRootDiscovery.Rejected -> put("rootFailure", JsonPrimitive(root.failure.name.lowercase()))
            }
        })

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
        val telemetry: ProductTelemetryDocument,
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

@Serializable
private sealed interface ProductTelemetryDocument {
    @Serializable
    @SerialName("enabled")
    data class Enabled(
        val state: String = "enabled",
        val format: String,
        val directoryPath: String,
        val traceFilePath: String,
    ) : ProductTelemetryDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val state: String = "rejected",
        val failure: String,
    ) : ProductTelemetryDocument
}

private fun ProductWorkspaceObservation.outputDocument(): ProductWorkspaceDocument = when (this) {
    is ProductWorkspaceObservation.RootRejected -> ProductWorkspaceDocument.RootRejected(
        failure.outputName(),
    )
    is ProductWorkspaceObservation.Observed -> ProductWorkspaceDocument.Observed(
        root.path.toString(),
        cache.outputDocument(),
        telemetry.outputDocument(),
    )
}

private fun RootSidecarCacheObservation.outputDocument(): ProductCacheDocument = when (this) {
    RootSidecarCacheObservation.Absent -> ProductCacheDocument.Absent()
    is RootSidecarCacheObservation.Identified -> ProductCacheDocument.Observed(
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

private fun ProductTelemetryObservation.outputDocument(): ProductTelemetryDocument = when (this) {
    is ProductTelemetryObservation.Enabled -> ProductTelemetryDocument.Enabled(
        format = output.format.identity,
        directoryPath = output.directoryPath.value,
        traceFilePath = output.traceFilePath.value,
    )
    is ProductTelemetryObservation.EndpointRejected -> ProductTelemetryDocument.Rejected(
        failure = failure.outputName(),
    )
    is ProductTelemetryObservation.OutputRejected -> ProductTelemetryDocument.Rejected(
        failure = failure.outputName(),
    )
    is ProductTelemetryObservation.CacheRejected -> ProductTelemetryDocument.Rejected(
        failure = failure.outputName(),
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

private val passiveFactory = CliJsonDocument.generated(JsonObject.serializer())

private fun compositionReason(failure: KastCliCompositionFailure): String = when (failure) {
    is InstalledCompositionFailure.IndexerHeapRejected -> "indexer-heap-${failure.failure.name.lowercase().replace('_', '-')}"
    is InstalledCompositionFailure.SavedConfigurationRejected -> "saved-configuration-${failure.failure.name.lowercase().replace('_', '-')}"

    is InstalledCompositionFailure.RuntimeProcessModeRejected -> "invalid-launchd-flag"
    is InstalledCompositionFailure.RuntimeDirectoryRejected -> "runtime-directory-${failure.failure.name.lowercase()}"
    is InstalledCompositionFailure.SidecarCacheRootRejected -> "cache-root-${failure.failure.name.lowercase()}"
    else -> failure.outputReason
}

private fun networkObservation(workspace: ProductWorkspaceObservation.Observed): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        val configured = InstalledNetworkBootstrap.observeConfiguration(
            workspace.root.path, System.getenv(),
        )
        when (configured) {
            is Refinement.Refined -> put("configuredSource", JsonPrimitive(configured.value.name.lowercase()))
            is Refinement.Rejected -> put("blockingReason", JsonPrimitive(configured.failure.name.lowercase()))
        }
        when (val cache = workspace.cache) {
            is RootSidecarCacheObservation.Identified -> {
                listOf("network", "network-daemon").forEach { consumer ->
                    val receipt = NetworkPolicyReceipt.observe(cache.status.cacheRoot.resolve(consumer))
                    put(consumer, receiptDocument(receipt))
                }
            }
            RootSidecarCacheObservation.Absent -> put("materialization", JsonPrimitive("absent"))
            is RootSidecarCacheObservation.Rejected -> put("materialization", JsonPrimitive("unavailable"))
        }
    }

private fun receiptDocument(receipt: NetworkPolicyObservation): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        fun field(key: String, value: String) = put(key, JsonPrimitive(value))
        when (receipt) {
            NetworkPolicyObservation.Absent -> field("state", "absent")
            is NetworkPolicyObservation.Rejected -> {
                field("state", "rejected"); field("failure", receipt.failure.name.lowercase())
            }
            is NetworkPolicyObservation.Recorded -> {
                field("state", "recorded")
                when (val authority = receipt.authority) {
                    RecordedNetworkAuthority.Gradle -> field("source", "explicit-gradle")
                    RecordedNetworkAuthority.TargetJvm -> field("source", "target-jvm")
                    is RecordedNetworkAuthority.Derived -> {
                        field("source", "derived"); field("digest", authority.digest); field("path", authority.path.toString())
                        put("provenance", JsonArray(authority.sources.sortedBy { it.name }.map { JsonPrimitive(it.name.lowercase()) }))
                    }
                }
            }
        }
    }

private fun ideObservation(control: SidecarProductIdentity): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        when (val discovered = InstalledIdeRuntimeDiscovery.discover(
            control.supportedRuntime, control.payloadDigest.value,
            IdeHomeSelection.standard(Path.of(System.getProperty("user.home"))),
        )) {
            is InstalledIdeRuntimeDiscoveryResult.Discovered -> {
                put("state", JsonPrimitive("admitted"))
                put("home", JsonPrimitive(discovered.runtime.home.toString()))
                put("jbr", JsonPrimitive(discovered.runtime.javaExecutable.parent.parent.toString()))
            }
            is InstalledIdeRuntimeDiscoveryResult.Rejected -> {
                put("state", JsonPrimitive("rejected"))
                put("failure", JsonPrimitive(ideFailure(discovered.failure)))
            }
        }
    }

private fun ideFailure(failure: IndexSeedFailure): String = when (failure) {
    is IndexSeedFailure.Incompatibility -> "incompatible-installation"
    IndexSeedFailure.Ambiguity -> "ambiguous-installation"
    IndexSeedFailure.MissingInstallation -> "missing-installation"
    IndexSeedFailure.RunningSourceIde -> "running-source-ide"
    IndexSeedFailure.ConsentAbsent -> "consent-absent"
    IndexSeedFailure.UnsupportedFilesystem -> "unsupported-filesystem"
    IndexSeedFailure.SourceMutation -> "source-mutation"
    IndexSeedFailure.CopyFailure -> "copy-failed"
    IndexSeedFailure.ValidationFailure -> "validation-failed"
}
