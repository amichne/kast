package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
enum class IdeLaunchFailure {
    METADATA_UNAVAILABLE,
    INVALID_METADATA,
    UNSUPPORTED_PLATFORM_LINE,
    MISSING_LAUNCHER,
    AMBIGUOUS_LAUNCHER,
    INVALID_LAUNCHER,
    EXECUTABLE_UNAVAILABLE,
    SAVED_SELECTION_REJECTED,
}

/** Vendor layouts remain distinct; a Linux directory is never treated as an application bundle. */
enum class SelectedIdeLayout(
    val metadata: String,
    val buildIdentity: String,
    val javaHome: String,
    val pluginDirectory: String,
    val operatingSystem: String,
) {
    MACOS_BUNDLE(
        "Resources/product-info.json",
        "Resources/build.txt",
        "jbr/Contents/Home",
        "Library/Application Support/JetBrains",
        "macOS",
    ),
    LINUX_DIRECTORY("product-info.json", "build.txt", "jbr", ".local/share/JetBrains", "Linux"),
}

/** Derived from the single selected home, never an independently configurable executable. */
@Serializable
sealed interface SelectedIdeLaunch {
    @Serializable
    @SerialName("resolved")
    data class Resolved
    internal constructor(
        val home: String,
        val bundle: String,
        val executable: String,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val hostPluginTarget: InstalledHostPluginTarget = InstalledHostPluginTarget.Unrecorded,
    ) : SelectedIdeLaunch {
        // The admitted vendor container is the standalone Linux home or the macOS parent bundle.
        // This derived property has no serialized backing field, preserving existing saved selections.
        val layout: SelectedIdeLayout
            get() = if (bundle == home) SelectedIdeLayout.LINUX_DIRECTORY else SelectedIdeLayout.MACOS_BUNDLE

        val bundledJavaHome: Path
            get() = Path.of(home).resolve(layout.javaHome)
    }

    @Serializable @SerialName("unavailable") data class Unavailable(val reason: IdeLaunchFailure) : SelectedIdeLaunch
}

/** The ordinary installer records its actual Host effect target; this is evidence, never a credential. */
@Serializable
sealed interface InstalledHostPluginTarget {
    @Serializable @SerialName("UNRECORDED") data object Unrecorded : InstalledHostPluginTarget

    @Serializable
    @SerialName("RECORDED")
    data class Recorded internal constructor(val root: String) : InstalledHostPluginTarget
}

enum class IdePluginRootFailure {
    SELECTION_REJECTED,
    METADATA_UNAVAILABLE,
    INVALID_METADATA,
    PROFILE_REJECTED,
}

/** Exact vendor profile path derived from the already selected IDE, never profile-name discovery. */
class SelectedIdePluginRoot internal constructor(val value: Path)

object SelectedIdeInstallation {
    private val metadataJson = Json { ignoreUnknownKeys = true }
    private const val MAXIMUM_METADATA_BYTES = 1_048_576

    fun admitPluginTarget(raw: String?, userHome: Path): Refinement<InstalledHostPluginTarget, IdePluginRootFailure> {
        if (raw == null) return Refinement.Refined(InstalledHostPluginTarget.Unrecorded)
        return try {
            val root = Path.of(raw)
            if (!root.isAbsolute || root.normalize() != root)
                return Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
            if (root.fileName?.toString() != "plugins")
                return Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
            val vendor = root.parent ?: return Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
            val expected = SelectedIdeLayout.entries.map { userHome.resolve(it.pluginDirectory) }
            if (
                vendor.parent !in expected ||
                    !vendor.fileName.toString().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            )
                Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
            else Refinement.Refined(InstalledHostPluginTarget.Recorded(root.toString()))
        } catch (_: java.nio.file.InvalidPathException) {
            Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        }
    }

    /** Combines the admitted launch and installer target without widening either constructor. */
    fun recordInstalledHostTarget(launch: SelectedIdeLaunch, target: InstalledHostPluginTarget): SelectedIdeLaunch =
        when (launch) {
            is SelectedIdeLaunch.Resolved -> launch.copy(hostPluginTarget = target)
            is SelectedIdeLaunch.Unavailable -> launch
        }

    fun matchesCurrentLaunch(selected: SelectedIdeLaunch.Resolved): Boolean =
        when (val current = resolve(Path.of(selected.home))) {
            is SelectedIdeLaunch.Unavailable -> false
            is SelectedIdeLaunch.Resolved ->
                current.home == selected.home &&
                    current.bundle == selected.bundle &&
                    current.executable == selected.executable
        }

    fun recordedPluginRoot(
        target: InstalledHostPluginTarget.Recorded,
        userHome: Path,
    ): Refinement<SelectedIdePluginRoot, IdePluginRootFailure> =
        try {
            when (val admitted = admitPluginTarget(target.root, userHome)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined -> physicalPluginRoot(Path.of(target.root), userHome)
            }
        } catch (_: IOException) {
            Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        } catch (_: SecurityException) {
            Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        }

    private fun physicalPluginRoot(
        root: Path,
        userHome: Path,
    ): Refinement<SelectedIdePluginRoot, IdePluginRootFailure> {
        if (!userHome.isAbsolute || userHome.toRealPath() != userHome)
            return Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        return if (root.toFile().canonicalFile.toPath() == root) Refinement.Refined(SelectedIdePluginRoot(root))
        else Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
    }

    fun pluginRoot(
        selected: SelectedIdeLaunch.Resolved,
        userHome: Path,
    ): Refinement<SelectedIdePluginRoot, IdePluginRootFailure> =
        try {
            admitPluginRoot(selected, userHome)
        } catch (_: IOException) {
            Refinement.Rejected(IdePluginRootFailure.METADATA_UNAVAILABLE)
        } catch (_: SecurityException) {
            Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        } catch (_: SerializationException) {
            Refinement.Rejected(IdePluginRootFailure.INVALID_METADATA)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(IdePluginRootFailure.INVALID_METADATA)
        }

    private fun admitPluginRoot(
        selected: SelectedIdeLaunch.Resolved,
        userHome: Path,
    ): Refinement<SelectedIdePluginRoot, IdePluginRootFailure> {
        if (!matchesCurrentLaunch(selected)) return Refinement.Rejected(IdePluginRootFailure.SELECTION_REJECTED)
        if (!userHome.isAbsolute || userHome.toRealPath() != userHome)
            return Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        val bytes =
            Files.newInputStream(Path.of(selected.home).resolve(selected.layout.metadata)).use {
                it.readNBytes(MAXIMUM_METADATA_BYTES + 1)
            }
        if (bytes.size > MAXIMUM_METADATA_BYTES) return Refinement.Rejected(IdePluginRootFailure.INVALID_METADATA)
        val product = metadataJson.decodeFromString<Product>(bytes.decodeToString(throwOnInvalidSequence = true))
        return derivePluginRoot(userHome, product.dataDirectoryName, selected.layout)
    }

    private fun derivePluginRoot(
        userHome: Path,
        directory: String?,
        layout: SelectedIdeLayout,
    ): Refinement<SelectedIdePluginRoot, IdePluginRootFailure> {
        if (directory == null || !directory.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            return Refinement.Rejected(IdePluginRootFailure.INVALID_METADATA)
        val root = userHome.resolve(layout.pluginDirectory).resolve(directory).resolve("plugins")
        return if (root.toFile().canonicalFile.toPath() != root)
            Refinement.Rejected(IdePluginRootFailure.PROFILE_REJECTED)
        else Refinement.Refined(SelectedIdePluginRoot(root))
    }

    /** Re-read on every explicit launch: supported in-place IDE updates retain their authority. */
    fun resolve(selectedHome: Path): SelectedIdeLaunch {
        return try {
            val home = selectedHome.toRealPath()
            val layouts = SelectedIdeLayout.entries.filter { Files.isRegularFile(home.resolve(it.metadata)) }
            if (layouts.isEmpty()) return unavailable(IdeLaunchFailure.METADATA_UNAVAILABLE)
            if (layouts.size != 1) return unavailable(IdeLaunchFailure.INVALID_METADATA)
            val layout = layouts.single()
            val metadata = home.resolve(layout.metadata)
            val bytes = Files.newInputStream(metadata).use { it.readNBytes(MAXIMUM_METADATA_BYTES + 1) }
            if (bytes.size > MAXIMUM_METADATA_BYTES) unavailable(IdeLaunchFailure.INVALID_METADATA)
            else
                resolve(
                    home,
                    metadataJson.decodeFromString<Product>(bytes.decodeToString(throwOnInvalidSequence = true)),
                    layout,
                )
        } catch (_: IOException) {
            unavailable(IdeLaunchFailure.METADATA_UNAVAILABLE)
        } catch (_: SecurityException) {
            unavailable(IdeLaunchFailure.METADATA_UNAVAILABLE)
        } catch (_: SerializationException) {
            unavailable(IdeLaunchFailure.INVALID_METADATA)
        } catch (_: IllegalArgumentException) {
            unavailable(IdeLaunchFailure.INVALID_METADATA)
        }
    }

    private fun resolve(home: Path, product: Product, layout: SelectedIdeLayout): SelectedIdeLaunch {
        if (!product.buildNumber.matches(Regex("262\\.[0-9]+(?:\\.[0-9]+)*")))
            return unavailable(IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE)
        val architectures =
            when (layout) {
                SelectedIdeLayout.MACOS_BUNDLE -> setOf("aarch64", "arm64")
                SelectedIdeLayout.LINUX_DIRECTORY -> setOf("amd64", "x86_64", "aarch64", "arm64")
            }
        val candidates = product.launch.filter { it.os == layout.operatingSystem && it.arch in architectures }
        if (candidates.isEmpty()) return unavailable(IdeLaunchFailure.MISSING_LAUNCHER)
        if (candidates.size != 1) return unavailable(IdeLaunchFailure.AMBIGUOUS_LAUNCHER)
        return resolveLauncher(home, layout, candidates.single())
    }

    private fun resolveLauncher(home: Path, layout: SelectedIdeLayout, launch: Launch): SelectedIdeLaunch {
        val relative = Path.of(launch.launcherPath)
        val launcherRoot =
            when (layout) {
                SelectedIdeLayout.MACOS_BUNDLE -> home.resolve("Resources")
                SelectedIdeLayout.LINUX_DIRECTORY -> home
            }
        val executable = launcherRoot.resolve(relative).normalize()
        if (
            layout == SelectedIdeLayout.MACOS_BUNDLE &&
                (home.fileName.toString() != "Contents" || !home.parent.fileName.toString().endsWith(".app"))
        )
            return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        if (relative.isAbsolute || !executable.startsWith(home)) return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            return unavailable(IdeLaunchFailure.EXECUTABLE_UNAVAILABLE)
        val physical = executable.toRealPath()
        if (!physical.startsWith(home)) return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        val container =
            when (layout) {
                SelectedIdeLayout.MACOS_BUNDLE -> home.parent
                SelectedIdeLayout.LINUX_DIRECTORY -> home
            }
        return SelectedIdeLaunch.Resolved(home.toString(), container.toString(), physical.toString())
    }

    private fun unavailable(reason: IdeLaunchFailure) = SelectedIdeLaunch.Unavailable(reason)

    @Serializable
    private data class Product(
        val buildNumber: String,
        val launch: List<Launch> = emptyList(),
        val dataDirectoryName: String? = null,
    )

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)
}
