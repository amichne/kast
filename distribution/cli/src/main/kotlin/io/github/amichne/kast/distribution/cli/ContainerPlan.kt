package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val CONTAINER_PROFILE = "kast-native"
internal const val CONTAINER_GRADLE_HOME = "/opt/kast/state/home/.gradle"
internal const val CONTAINER_WORKSPACE_LABEL = "io.kast.workspace"

@Serializable
internal data class ColimaProfile(val name: String, val status: String, val arch: String, val runtime: String)

/** The containerd image document keeps the immutable target and its selected native config together. */
@Serializable
internal data class ContainerImageDocument(
    @SerialName("Image") val image: ContainerImageTarget,
    @SerialName("ImageConfig") val configuration: ContainerImageConfiguration,
)

@Serializable
internal data class ContainerImageTarget(
    @SerialName("Name") val name: String,
    @SerialName("Target") val target: ContainerImageDescriptor,
)

@Serializable internal data class ContainerImageDescriptor(val digest: String)

@Serializable
internal data class ContainerImageConfiguration(val architecture: String, val os: String, val config: ContainerConfig)

@Serializable
internal data class ContainerConfig(
    @SerialName("User") val user: String,
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
)

internal class ContainerImage private constructor(val reference: String, val digest: String, val source: String) {
    companion object {
        fun admitAliases(
            documents: List<ContainerImageDocument>,
            architecture: ContainerArchitecture,
            selection: ContainerImageSelection,
            reference: String,
        ): Refinement<ContainerImage, ContainerFailure> {
            if (documents.map { it.image.target to it.configuration }.distinct().size != 1)
                return Refinement.Rejected(ContainerFailure.IMAGE_REJECTED)
            val document = documents.firstOrNull { it.image.name == reference } ?: documents.first()
            return admit(document, architecture, selection)
        }

        fun admit(
            document: ContainerImageDocument,
            architecture: ContainerArchitecture,
            selection: ContainerImageSelection = ContainerImageSelection.Local,
        ): Refinement<ContainerImage, ContainerFailure> {
            val configuration = document.configuration
            val name = document.image.name.substringBefore('@')
            val repository = if (name.lastIndexOf(':') > name.lastIndexOf('/')) name.substringBeforeLast(':') else name
            val digest = document.image.target.digest
            return if (
                !nativeConfiguration(configuration, architecture) ||
                    !workspaceLayout(configuration.config) ||
                    !selectedTarget(repository, digest, selection)
            )
                Refinement.Rejected(ContainerFailure.IMAGE_REJECTED)
            else Refinement.Refined(ContainerImage("$repository@$digest", digest, document.image.name))
        }

        private fun nativeConfiguration(
            configuration: ContainerImageConfiguration,
            architecture: ContainerArchitecture,
        ): Boolean = configuration.os == "linux" && configuration.architecture == architecture.oci

        private fun workspaceLayout(config: ContainerConfig): Boolean =
            config.user == "developer" && config.labels["io.kast.dev-container"] == "3"

        private fun selectedTarget(repository: String, digest: String, selection: ContainerImageSelection): Boolean =
            Regex("sha256:[a-f0-9]{64}").matches(digest) &&
                Regex("[a-z0-9][a-z0-9._:/-]*").matches(repository) &&
                matchesSelection(digest, selection)

        private fun matchesSelection(digest: String, selection: ContainerImageSelection): Boolean =
            when (selection) {
                ContainerImageSelection.Local -> true
                is ContainerImageSelection.Digest ->
                    selection.reference.substringAfterLast('@').removePrefix("sha256:") ==
                        digest.removePrefix("sha256:")
            }
    }
}

@Serializable
internal data class ContainerDocument(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Image") val image: String,
    @SerialName("Config") val config: ContainerConfig,
    @SerialName("Mounts") val mounts: List<ContainerMount>,
    @SerialName("HostConfig") val host: ContainerHostConfig,
    @SerialName("State") val state: ContainerState,
)

@Serializable
internal data class ContainerMount(
    @SerialName("Type") val type: String,
    @SerialName("Source") val source: String,
    @SerialName("Destination") val destination: String,
    @SerialName("Name") val name: String = "",
    @SerialName("RW") val writable: Boolean,
)

@Serializable
internal data class ContainerHostConfig(
    @SerialName("NetworkMode") val network: String,
    @SerialName("Privileged") val privileged: Boolean,
)

@Serializable
internal data class ContainerState(
    @SerialName("Running") val running: Boolean,
    @SerialName("Paused") val paused: Boolean,
    @SerialName("Restarting") val restarting: Boolean,
)

/** Workspace admission precedes image lookup and preserves the selected immutable image. */
internal class ContainerScope
private constructor(
    val document: ContainerDocument,
    val imageSelection: ContainerImageSelection.Digest,
) {
    companion object {
        fun admit(
            document: ContainerDocument,
            workspace: ContainerWorkspace,
            name: ContainerWorkspaceName,
            architecture: ContainerArchitecture,
            network: ContainerNetwork,
        ): Refinement<ContainerScope, ContainerFailure> {
            val captured =
                document.copy(
                    config = document.config.copy(labels = document.config.labels.toMap()),
                    mounts = document.mounts.toList(),
                )
            val key = workspace.key(name, architecture)
            if (
                !ownedIdentity(captured, workspace, key) ||
                    !workspacePolicy(captured, network) ||
                    !workspaceMounts(captured.mounts, workspace, key)
            )
                return Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT)
            return when (val selection = ContainerImageSelection.Digest.parse(captured.image)) {
                is Refinement.Rejected -> Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT)
                is Refinement.Refined -> Refinement.Refined(ContainerScope(captured, selection.value))
            }
        }

        private fun ownedIdentity(document: ContainerDocument, workspace: ContainerWorkspace, key: String): Boolean =
            document.name.removePrefix("/") == "kast-work-$key" &&
                Regex("[a-f0-9]{64}").matches(document.id) &&
                ownedLabels(document.config.labels, workspace, key)

        private fun ownedLabels(labels: Map<String, String>, workspace: ContainerWorkspace, key: String): Boolean =
            labels[CONTAINER_WORKSPACE_LABEL] == "1" &&
                labels["io.kast.workspace.key"] == key &&
                labels["io.kast.workspace.root"] == workspace.root.toString()

        private fun workspacePolicy(document: ContainerDocument, network: ContainerNetwork): Boolean =
            configuredPolicy(document, network) && !document.state.paused && !document.state.restarting

        private fun configuredPolicy(document: ContainerDocument, network: ContainerNetwork): Boolean =
            document.config.user == "developer" &&
                document.host.network == network.argument &&
                !document.host.privileged

        private fun workspaceMounts(mounts: List<ContainerMount>, workspace: ContainerWorkspace, key: String): Boolean =
            mounts.size == workspace.mounts.size + 1 &&
                bindMounts(mounts.filter { it.type == "bind" }, workspace) &&
                cacheMount(mounts.filter { it.type == "volume" }, key)

        private fun bindMounts(mounts: List<ContainerMount>, workspace: ContainerWorkspace): Boolean {
            val paths = workspace.mounts.map { it.toString() }.toSet()
            return mounts.size == paths.size &&
                mounts.map { it.source }.toSet() == paths &&
                mounts.all { it.source == it.destination && it.writable }
        }

        private fun cacheMount(mounts: List<ContainerMount>, key: String): Boolean {
            val mount = mounts.singleOrNull() ?: return false
            return mount.name == "kast-gradle-$key" && mount.destination == CONTAINER_GRADLE_HOME && mount.writable
        }
    }
}

internal class ContainerPlan(
    val workspace: ContainerWorkspace,
    private val name: ContainerWorkspaceName,
    private val architecture: ContainerArchitecture,
    val image: ContainerImage,
    val network: ContainerNetwork,
) {
    val key = workspace.key(name, architecture)
    val container = "kast-work-$key"
    val cache = "kast-gradle-$key"

    val createArguments: List<String>
        get() =
            listOf(
                "create",
                "--name",
                container,
                "--pull=never",
                "--init",
                "--label",
                "$CONTAINER_WORKSPACE_LABEL=1",
                "--label",
                "io.kast.workspace.key=$key",
                "--label",
                "io.kast.workspace.root=${workspace.root}",
                "--label",
                "io.kast.image=${image.digest}",
                "--workdir",
                workspace.root.toString(),
                "--network",
                network.argument,
                "--env",
                "GRADLE_USER_HOME=$CONTAINER_GRADLE_HOME",
            ) +
                workspace.mounts.flatMap { listOf("--mount", "type=bind,src=$it,dst=$it") } +
                listOf(
                    "--mount",
                    "type=volume,src=$cache,dst=$CONTAINER_GRADLE_HOME",
                    "--entrypoint",
                    "/bin/sleep",
                    image.reference,
                    "infinity",
                )

    fun admit(document: ContainerDocument): Refinement<RetainedContainer, ContainerFailure> =
        when (val scope = ContainerScope.admit(document, workspace, name, architecture, network)) {
            is Refinement.Rejected -> scope
            is Refinement.Refined -> RetainedContainer.admit(scope.value, image)
        }

    /** Only this plan's admission can produce an effect target. */
    class RetainedContainer private constructor(val id: String, val running: Boolean) {
        companion object {
            fun admit(
                scope: ContainerScope,
                image: ContainerImage,
            ): Refinement<RetainedContainer, ContainerFailure> =
                if (
                    scope.document.image != image.reference ||
                        scope.document.config.labels["io.kast.image"] != image.digest
                )
                    Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT)
                else Refinement.Refined(RetainedContainer(scope.document.id, scope.document.state.running))
        }
    }
}
