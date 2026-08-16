package io.github.amichne.kast.indexer.project

import com.intellij.openapi.application.PathManager
import io.github.amichne.kast.api.client.WorkspaceIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

class IndexerProjectLayout private constructor(
    val workspaceRoot: Path,
    val storageRoot: Path,
    val inheritedStorageLeaseFileDescriptor: Int?,
    val bootstrapToken: UUID?,
    val admittedWorkspaceLayout: AdmittedIndexerWorkspaceLayout,
) {
    val projectIdentityDirectory: Path = storageRoot.resolve("project-identity")
    val gradleProjectCacheDirectory: Path = storageRoot.resolve("gradle-project-cache")
    val storageLeaseFile: Path = storageRoot.resolve("storage.lease")
    val ideaConfigDirectory: Path = storageRoot.resolve("idea-config")
    val ideaSystemDirectory: Path = storageRoot.resolve("idea-system")
    val ideaLogDirectory: Path = storageRoot.resolve("idea-log")
    val pluginsDirectory: Path = storageRoot.resolve("plugins")
    val launchManifestFile: Path = storageRoot.resolve("launch-manifest.json")

    init {
        require(storageRoot != workspaceRoot) {
            "Kast indexer storage must differ from the exact source root: $workspaceRoot"
        }
        require(!storageRoot.startsWith(workspaceRoot) && !workspaceRoot.startsWith(storageRoot)) {
            "Kast indexer storage must be disjoint from the exact source root: $workspaceRoot"
        }
    }

    fun prepare() {
        writableDirectories().forEach { expected ->
            require(!Files.isSymbolicLink(expected)) {
                "Kast indexer storage path must not be a symbolic link: $expected"
            }
            Files.createDirectories(expected)
            val actual = expected.toRealPath()
            require(actual == expected && actual.startsWith(storageRoot)) {
                "Kast indexer storage $actual escaped its canonical root $storageRoot"
            }
        }
        require(!Files.isSymbolicLink(storageLeaseFile)) {
            "Kast indexer storage path must not be a symbolic link: $storageLeaseFile"
        }
        if (Files.exists(storageLeaseFile)) {
            require(storageLeaseFile.toRealPath().parent == storageRoot) {
                "Kast indexer lease escaped its canonical root $storageRoot"
            }
        }
    }

    fun requireOwnedIdeaPaths() {
        requireOwnedIdeaPaths(
            configDirectory = PathManager.getConfigDir(),
            systemDirectory = PathManager.getSystemDir(),
            logDirectory = PathManager.getLogDir(),
            pluginsDirectory = PathManager.getPluginsDir(),
        )
    }

    fun workspaceIdentity(descriptorDirectory: Path): WorkspaceIdentity =
        WorkspaceIdentity.fromAdmittedWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            workspaceDataDirectory = admittedWorkspaceLayout.workspaceDataDirectory,
            repositoryDataDirectory = admittedWorkspaceLayout.repositoryDataDirectory,
            descriptorDirectory = descriptorDirectory,
        )

    fun publishBootstrapReceipt() {
        val token = bootstrapToken ?: return
        val processId = ProcessHandle.current().pid()
        val receiptDirectory = storageRoot.resolve("bootstrap")
        require(!Files.isSymbolicLink(receiptDirectory)) {
            "Kast indexer bootstrap directory must not be a symbolic link: $receiptDirectory"
        }
        Files.createDirectories(receiptDirectory)
        require(receiptDirectory.toRealPath() == receiptDirectory) {
            "Kast indexer bootstrap directory escaped canonical storage: $receiptDirectory"
        }
        val receipt = receiptDirectory.resolve("$token.json")
        val temporary = receiptDirectory.resolve(".$token.$processId.tmp")
        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("token", token.toString())
            put("pid", processId)
            put("canonicalWorkspaceRoot", workspaceRoot.toString())
            put("canonicalStorageRoot", storageRoot.toString())
        }
        Files.writeString(
            temporary,
            document.toString(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun requireOwnedIdeaPaths(
        configDirectory: Path,
        systemDirectory: Path,
        logDirectory: Path,
        pluginsDirectory: Path,
    ) {
        val expected = mapOf(
            "config" to ideaConfigDirectory,
            "system" to ideaSystemDirectory,
            "log" to ideaLogDirectory,
            "plugins" to this.pluginsDirectory,
        )
        val actual = mapOf(
            "config" to configDirectory,
            "system" to systemDirectory,
            "log" to logDirectory,
            "plugins" to pluginsDirectory,
        )
        actual.forEach { (name, path) ->
            val realPath = path.toRealPath()
            require(realPath == expected.getValue(name) && realPath.startsWith(storageRoot)) {
                "IDEA $name path $realPath is not owned by Kast indexer storage $storageRoot"
            }
        }
    }

    private fun writableDirectories(): List<Path> = listOf(
        projectIdentityDirectory,
        gradleProjectCacheDirectory,
        ideaConfigDirectory,
        ideaSystemDirectory,
        ideaLogDirectory,
        pluginsDirectory,
        storageRoot.resolve("bootstrap"),
    )

    companion object {
        private const val STORAGE_ROOT_PREFIX = "--indexer-storage-root="
        private const val STORAGE_LEASE_FD_PREFIX = "--storage-lease-fd="
        private const val BOOTSTRAP_TOKEN_PREFIX = "--bootstrap-token="

        fun create(
            workspaceRoot: Path,
            storageRoot: Path,
            inheritedStorageLeaseFileDescriptor: Int? = null,
            bootstrapToken: UUID? = null,
            workspaceDataDirectory: Path? = null,
            repositoryDataDirectory: Path? = null,
        ): IndexerProjectLayout {
            val canonicalWorkspaceRoot = workspaceRoot.toRealPath()
            val absoluteStorageRoot = storageRoot.toAbsolutePath().normalize()
            val projectedStorageRoot = projectedRealPath(absoluteStorageRoot)
            requireDisjoint(canonicalWorkspaceRoot, projectedStorageRoot)
            Files.createDirectories(absoluteStorageRoot)
            val canonicalStorageRoot = absoluteStorageRoot.toRealPath()
            requireDisjoint(canonicalWorkspaceRoot, canonicalStorageRoot)
            val admittedWorkspaceLayout = AdmittedIndexerWorkspaceLayout.create(
                workspaceRoot = canonicalWorkspaceRoot,
                workspaceDataDirectory = workspaceDataDirectory
                    ?: canonicalStorageRoot.parent.resolve("test-workspace-data"),
                repositoryDataDirectory = repositoryDataDirectory,
            )
            return IndexerProjectLayout(
                workspaceRoot = canonicalWorkspaceRoot,
                storageRoot = canonicalStorageRoot,
                inheritedStorageLeaseFileDescriptor = inheritedStorageLeaseFileDescriptor,
                bootstrapToken = bootstrapToken,
                admittedWorkspaceLayout = admittedWorkspaceLayout,
            ).also(IndexerProjectLayout::prepare)
        }

        fun parse(args: List<String>, workspaceRoot: Path): IndexerProjectLayout {
            val storageRoot = args.requiredPath(STORAGE_ROOT_PREFIX)
            val manifest = readLaunchManifest(
                manifestFile = storageRoot.toRealPath().resolve("launch-manifest.json"),
                expectedWorkspaceRoot = workspaceRoot.toRealPath(),
                expectedStorageRoot = storageRoot.toRealPath(),
            )
            return create(
                workspaceRoot = workspaceRoot,
                storageRoot = storageRoot,
                inheritedStorageLeaseFileDescriptor = args.requiredInt(STORAGE_LEASE_FD_PREFIX),
                bootstrapToken = args.requiredUuid(BOOTSTRAP_TOKEN_PREFIX),
                workspaceDataDirectory = manifest.workspaceDataDirectory,
                repositoryDataDirectory = manifest.repositoryDataDirectory,
            )
        }

        fun isLayoutArgument(argument: String): Boolean =
            argument.startsWith(STORAGE_ROOT_PREFIX) ||
                argument.startsWith(STORAGE_LEASE_FD_PREFIX) ||
                argument.startsWith(BOOTSTRAP_TOKEN_PREFIX)

        private fun List<String>.requiredPath(prefix: String): Path {
            val matches = filter { it.startsWith(prefix) }
            require(matches.size == 1) { "Indexer launch requires exactly one $prefix argument" }
            val raw = matches.single().removePrefix(prefix)
            require(raw.isNotBlank()) { "Indexer launch path for $prefix must not be blank" }
            return Path.of(raw)
        }

        private fun List<String>.requiredInt(prefix: String): Int {
            val matches = filter { it.startsWith(prefix) }
            require(matches.size == 1) { "Indexer launch requires exactly one $prefix argument" }
            return matches.single().removePrefix(prefix).toInt().also { value ->
                require(value >= 0) { "Indexer launch file descriptor for $prefix must not be negative" }
            }
        }

        private fun List<String>.requiredUuid(prefix: String): UUID {
            val matches = filter { it.startsWith(prefix) }
            require(matches.size == 1) { "Indexer launch requires exactly one $prefix argument" }
            val raw = matches.single().removePrefix(prefix)
            return UUID.fromString(raw).also { value ->
                require(value.toString() == raw) { "Indexer launch token for $prefix is not canonical" }
            }
        }

        internal fun projectedRealPath(path: Path): Path {
            var existing = path
            val suffix = ArrayDeque<Path>()
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                suffix.addFirst(
                    requireNotNull(existing.fileName) { "Cannot resolve Kast indexer storage $path" },
                )
                existing = requireNotNull(existing.parent) { "Cannot resolve Kast indexer storage $path" }
            }
            return suffix.fold(existing.toRealPath(), Path::resolve)
        }

        private fun requireDisjoint(workspaceRoot: Path, storageRoot: Path) {
            require(!storageRoot.startsWith(workspaceRoot) && !workspaceRoot.startsWith(storageRoot)) {
                "Kast indexer storage $storageRoot must be disjoint from the exact source root $workspaceRoot"
            }
        }

        private fun readLaunchManifest(
            manifestFile: Path,
            expectedWorkspaceRoot: Path,
            expectedStorageRoot: Path,
        ): AdmittedIndexerWorkspaceLayout {
            require(!Files.isSymbolicLink(manifestFile)) {
                "Kast indexer launch manifest must not be a symbolic link: $manifestFile"
            }
            val document = Json.parseToJsonElement(Files.readString(manifestFile)).jsonObject
            require(document.getValue("schemaVersion").jsonPrimitive.content.toInt() == 1) {
                "Unsupported Kast indexer launch manifest schema"
            }
            val admittedWorkspaceRoot = document.requiredAbsolutePath("canonicalWorkspaceRoot").toRealPath()
            val admittedStorageRoot = document.requiredAbsolutePath("canonicalStorageRoot").toRealPath()
            require(admittedWorkspaceRoot == expectedWorkspaceRoot) {
                "Kast indexer launch manifest workspace does not match $expectedWorkspaceRoot"
            }
            require(admittedStorageRoot == expectedStorageRoot) {
                "Kast indexer launch manifest storage does not match $expectedStorageRoot"
            }
            val repositoryDataDirectory = document["repositoryDataDirectory"]
                ?.takeUnless { element -> element.toString() == "null" }
                ?.jsonPrimitive
                ?.content
                ?.let(Path::of)
            return AdmittedIndexerWorkspaceLayout.create(
                workspaceRoot = expectedWorkspaceRoot,
                workspaceDataDirectory = document.requiredAbsolutePath("workspaceDataDirectory"),
                repositoryDataDirectory = repositoryDataDirectory,
            )
        }

        private fun Map<String, kotlinx.serialization.json.JsonElement>.requiredAbsolutePath(name: String): Path {
            val path = Path.of(getValue(name).jsonPrimitive.content)
            require(path.isAbsolute) { "Kast indexer launch manifest $name must be absolute" }
            return path.normalize()
        }
    }
}

class AdmittedIndexerWorkspaceLayout private constructor(
    val workspaceDataDirectory: Path,
    val repositoryDataDirectory: Path?,
) {
    companion object {
        fun create(
            workspaceRoot: Path,
            workspaceDataDirectory: Path,
            repositoryDataDirectory: Path?,
        ): AdmittedIndexerWorkspaceLayout {
            val workspaceData = admittedPath(workspaceDataDirectory)
            val repositoryData = repositoryDataDirectory?.let(::admittedPath)
            require(!workspaceData.startsWith(workspaceRoot) && !workspaceRoot.startsWith(workspaceData)) {
                "Admitted analysis storage must be disjoint from the exact source root $workspaceRoot"
            }
            repositoryData?.let { path ->
                require(!path.startsWith(workspaceRoot) && !workspaceRoot.startsWith(path)) {
                    "Admitted repository storage must be disjoint from the exact source root $workspaceRoot"
                }
            }
            return AdmittedIndexerWorkspaceLayout(workspaceData, repositoryData)
        }

        private fun admittedPath(path: Path): Path {
            require(path.isAbsolute) { "Admitted analysis storage path must be absolute: $path" }
            val normalized = path.normalize()
            val projected = IndexerProjectLayout.projectedRealPath(normalized)
            require(projected == normalized) {
                "Admitted analysis storage path is not canonical: $normalized"
            }
            return normalized
        }
    }
}
