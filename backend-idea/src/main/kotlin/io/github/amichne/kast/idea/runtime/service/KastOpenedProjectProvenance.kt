package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequestId
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

internal object KastOpenedProjectProvenance {
    private val marker = Key.create<Boolean>("kast.agent.opened.project")

    fun isMarked(project: Project): Boolean = project.getUserData(marker) == true

    fun mark(project: Project) {
        project.putUserData(marker, true)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WindowManager.getInstance().getFrame(project)?.let { frame ->
                val title = frame.title.orEmpty()
                if (!title.endsWith(TITLE_SUFFIX)) {
                    frame.title = "$title$TITLE_SUFFIX"
                }
            }
        }
    }

    private const val TITLE_SUFFIX = " — Kast Agent"
}

internal class KastOpenProjectRequestStore(
    config: KastConfig,
    private val timeProvider: OpenProjectRequestTimeProvider = OpenProjectRequestTimeProvider.system,
    private val processId: IdeaProcessId = IdeaProcessId.current(),
    private val productCode: IdeaProductCode = IdeaProductCode.current(),
) {
    private val directory = Path.of(config.paths.runtimeDir.value).resolve("idea-open-requests")
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun consume(
        canonicalRoot: RuntimeOpenProjectRoot,
        requestId: RuntimeOpenProjectRequestId,
        allowUntargeted: Boolean,
    ): Boolean {
        val requestPath = directory.resolve("$requestId.json")
        if (!isPrivateRegularFile(requestPath)) return false
        val request = runCatching {
            json.decodeFromString<StoredOpenProjectRequest>(Files.readString(requestPath))
        }.onFailure { error ->
            LOG.warn("Ignoring invalid Kast project-open request at $requestPath", error)
        }.getOrNull() ?: return false
        if (
            request.requestId != requestId ||
            request.canonicalRoot != canonicalRoot ||
            request.expiresAt < timeProvider.now() ||
            (request.targetPid == null && !allowUntargeted) ||
            (request.targetPid != null && request.targetPid != processId) ||
            (
                request.targetPid == null &&
                    (request.targetProductCode == null || request.targetProductCode != productCode)
            )
        ) {
            return false
        }
        val consumed = directory.resolve(
            ".$requestId-${RuntimeOpenProjectRequestId.random()}.consumed",
        )
        return runCatching {
            Files.move(requestPath, consumed, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(consumed)
            true
        }.onFailure { error ->
            LOG.warn("Could not atomically consume Kast project-open request at $requestPath", error)
        }.getOrDefault(false)
    }

    fun consumeUntargetedForProject(canonicalRoot: RuntimeOpenProjectRoot): Boolean {
        if (!Files.isDirectory(directory)) return false
        return Files.list(directory).use { paths ->
            paths
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .map { path -> path.fileName.toString().removeSuffix(".json") }
                .map { raw -> runCatching { RuntimeOpenProjectRequestId.parse(raw) }.getOrNull() }
                .filter { requestId -> requestId != null }
                .anyMatch { requestId ->
                    consume(canonicalRoot, requireNotNull(requestId), allowUntargeted = true)
                }
        }
    }

    private fun isPrivateRegularFile(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return false
        return permissions.none { permission -> permission in NON_OWNER_PERMISSIONS }
    }

    private companion object {
        private val LOG = Logger.getInstance(KastOpenProjectRequestStore::class.java)
        private val NON_OWNER_PERMISSIONS = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}

internal fun interface OpenProjectRequestTimeProvider {
    fun now(): OpenProjectRequestInstant

    companion object {
        val system = OpenProjectRequestTimeProvider {
            OpenProjectRequestInstant.fromEpochMillis(Instant.now().toEpochMilli())
        }
    }
}

@Serializable
@JvmInline
internal value class OpenProjectRequestInstant private constructor(
    val epochMillis: Long,
) : Comparable<OpenProjectRequestInstant> {
    init {
        require(epochMillis >= 0) { "Open-project request time must not be negative" }
    }

    override fun compareTo(other: OpenProjectRequestInstant): Int =
        epochMillis.compareTo(other.epochMillis)

    companion object {
        fun fromEpochMillis(value: Long): OpenProjectRequestInstant =
            OpenProjectRequestInstant(value)
    }
}

@Serializable
@JvmInline
internal value class IdeaProcessId private constructor(
    val value: Long,
) {
    init {
        require(value > 0) { "IDEA process ID must be positive" }
    }

    companion object {
        fun of(value: Long): IdeaProcessId = IdeaProcessId(value)

        fun current(): IdeaProcessId = IdeaProcessId(ProcessHandle.current().pid())
    }
}

@Serializable
@JvmInline
internal value class IdeaProductCode private constructor(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "IDEA product code must not be blank" }
    }

    companion object {
        fun of(value: String): IdeaProductCode = IdeaProductCode(value)

        fun current(): IdeaProductCode =
            IdeaProductCode(ApplicationInfo.getInstance().build.productCode)
    }
}

@Serializable
private data class StoredOpenProjectRequest(
    val canonicalRoot: RuntimeOpenProjectRoot,
    val requestId: RuntimeOpenProjectRequestId,
    val targetPid: IdeaProcessId? = null,
    val targetProductCode: IdeaProductCode? = null,
    @SerialName("expiresAtEpochMillis")
    val expiresAt: OpenProjectRequestInstant,
)
