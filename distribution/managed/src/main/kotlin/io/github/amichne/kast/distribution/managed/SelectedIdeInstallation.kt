package io.github.amichne.kast.distribution.managed

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
}

/** Derived from the single selected home, never an independently configurable executable. */
@Serializable
sealed interface SelectedIdeLaunch {
    @Serializable
    @SerialName("resolved")
    data class Resolved internal constructor(val home: String, val bundle: String, val executable: String) :
        SelectedIdeLaunch

    @Serializable @SerialName("unavailable") data class Unavailable(val reason: IdeLaunchFailure) : SelectedIdeLaunch
}

object SelectedIdeInstallation {
    private val metadataJson = Json { ignoreUnknownKeys = true }
    private const val MAXIMUM_METADATA_BYTES = 1_048_576

    /** Re-read on every explicit launch: supported in-place IDE updates retain their authority. */
    fun resolve(selectedHome: Path): SelectedIdeLaunch =
        try {
            val home = selectedHome.toRealPath()
            val metadata = home.resolve("Resources/product-info.json")
            val bytes = Files.newInputStream(metadata).use { it.readNBytes(MAXIMUM_METADATA_BYTES + 1) }
            if (bytes.size > MAXIMUM_METADATA_BYTES) unavailable(IdeLaunchFailure.INVALID_METADATA)
            else
                resolve(
                    home,
                    metadataJson.decodeFromString<Product>(bytes.decodeToString(throwOnInvalidSequence = true)),
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

    private fun resolve(home: Path, product: Product): SelectedIdeLaunch {
        if (!product.buildNumber.matches(Regex("262\\.[0-9]+(?:\\.[0-9]+)*")))
            return unavailable(IdeLaunchFailure.UNSUPPORTED_PLATFORM_LINE)
        val candidates = product.launch.filter { it.os == "macOS" && it.arch in setOf("aarch64", "arm64") }
        if (candidates.isEmpty()) return unavailable(IdeLaunchFailure.MISSING_LAUNCHER)
        if (candidates.size != 1) return unavailable(IdeLaunchFailure.AMBIGUOUS_LAUNCHER)
        val relative = Path.of(candidates.single().launcherPath)
        val executable = home.resolve("Resources").resolve(relative).normalize()
        if (home.fileName.toString() != "Contents" || !home.parent.fileName.toString().endsWith(".app"))
            return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        if (relative.isAbsolute || !executable.startsWith(home)) return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            return unavailable(IdeLaunchFailure.EXECUTABLE_UNAVAILABLE)
        val physical = executable.toRealPath()
        if (!physical.startsWith(home)) return unavailable(IdeLaunchFailure.INVALID_LAUNCHER)
        return SelectedIdeLaunch.Resolved(home.toString(), home.parent.toString(), physical.toString())
    }

    private fun unavailable(reason: IdeLaunchFailure) = SelectedIdeLaunch.Unavailable(reason)

    @Serializable private data class Product(val buildNumber: String, val launch: List<Launch> = emptyList())

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)
}
