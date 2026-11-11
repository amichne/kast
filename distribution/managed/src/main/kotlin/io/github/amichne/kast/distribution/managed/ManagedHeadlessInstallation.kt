package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Assembly provenance is separate from mutable state and live Host readiness. */
@Serializable
data class ManagedHeadlessAssemblyRecord(
    val schemaVersion: Int,
    val installation: String,
    val distributionSha256: String,
    val bootstrapSha256: String,
    val platformBuild: String,
    val kotlinPluginBuild: String,
)

enum class ManagedHeadlessInstallationFailure {
    INVALID_PATH,
    SHARED_STATE,
    INVALID_ASSEMBLY,
    BOOTSTRAP_CHANGED,
}

/** The selected assembly and private state travel together to the process adapter. */
class ManagedHeadlessInstallation
private constructor(
    val launch: SelectedIdeLaunch.Resolved,
    val state: Path,
    val userHome: Path,
    val plugins: Path,
    val assembly: ManagedHeadlessAssemblyRecord,
    val gradleHome: Path,
) {
    val config: Path
        get() = plugins.parent

    val options: Path
        get() = state.resolve("host.vmoptions")

    companion object {
        fun resolve(
            launch: SelectedIdeLaunch.Resolved,
            state: Path,
            userHome: Path,
        ): Refinement<ManagedHeadlessInstallation, ManagedHeadlessInstallationFailure> =
            try {
                admit(launch, state, userHome)
            } catch (_: java.io.IOException) {
                Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
            } catch (_: kotlinx.serialization.SerializationException) {
                Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            }

        private const val MAXIMUM_RECORD_BYTES = 65_536

        private fun admit(
            launch: SelectedIdeLaunch.Resolved,
            state: Path,
            userHome: Path,
        ): Refinement<ManagedHeadlessInstallation, ManagedHeadlessInstallationFailure> {
            when (val paths = admitPaths(launch, state, userHome)) {
                is Refinement.Rejected -> return paths
                is Refinement.Refined -> Unit
            }
            val home = Path.of(launch.home)
            val record =
                when (val parsed = readRecord(home)) {
                    is Refinement.Rejected -> return parsed
                    is Refinement.Refined -> parsed.value
                }
            when (val bootstrap = admitBootstrap(home, record.bootstrapSha256)) {
                is Refinement.Rejected -> return bootstrap
                is Refinement.Refined -> Unit
            }
            val plugins =
                when (val root = SelectedIdeInstallation.pluginRoot(launch, userHome)) {
                    is Refinement.Refined -> root.value.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
                }
            if (plugins.toRealPath() != plugins || !plugins.startsWith(userHome))
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            val gradleHome =
                when (val selected = readGradleHome(state, userHome)) {
                    is Refinement.Rejected -> return selected
                    is Refinement.Refined -> selected.value
                }
            return Refinement.Refined(ManagedHeadlessInstallation(launch, state, userHome, plugins, record, gradleHome))
        }

        private fun readGradleHome(state: Path, userHome: Path): Refinement<Path, ManagedHeadlessInstallationFailure> {
            val path = state.resolve("gradle-home.json")
            if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return Refinement.Refined(userHome.resolve(".gradle"))
            if (path.toRealPath() != path || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAXIMUM_RECORD_BYTES + 1) }
            if (bytes.size > MAXIMUM_RECORD_BYTES)
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
            val gradle = Path.of(Json.decodeFromString<ManagedHeadlessGradleHome>(bytes.decodeToString()).path)
            if (!gradle.isAbsolute || gradle.normalize() != gradle)
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            return if (gradle.toRealPath() == gradle && Files.isDirectory(gradle)) Refinement.Refined(gradle)
            else Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
        }

        private fun admitPaths(
            launch: SelectedIdeLaunch.Resolved,
            state: Path,
            userHome: Path,
        ): Refinement<Unit, ManagedHeadlessInstallationFailure> {
            val home = Path.of(launch.home)
            val paths =
                listOf(state, home, userHome, launch.bundledJavaHome) +
                    listOf("system", "log", "tmp").map(state::resolve)
            if (paths.any { !it.isAbsolute || it.normalize() != it || it.toRealPath() != it })
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            return if (userHome != state.resolve("home") || state.startsWith(home) || home.startsWith(state))
                Refinement.Rejected(ManagedHeadlessInstallationFailure.SHARED_STATE)
            else Refinement.Refined(Unit)
        }

        private fun readRecord(
            home: Path
        ): Refinement<ManagedHeadlessAssemblyRecord, ManagedHeadlessInstallationFailure> {
            val bytes =
                Files.newInputStream(home.resolve("kast-headless.json")).use { it.readNBytes(MAXIMUM_RECORD_BYTES + 1) }
            if (bytes.size > MAXIMUM_RECORD_BYTES)
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
            val record = Json.decodeFromString<ManagedHeadlessAssemblyRecord>(bytes.decodeToString())
            val identityValid = record.schemaVersion == 1 && record.installation == home.toString()
            val platformValid =
                Regex("(?:262|263)\\.[0-9]+(?:\\.[0-9]+)*").matches(record.platformBuild) &&
                    record.kotlinPluginBuild == record.platformBuild + "-IJ"
            val digest = Regex("[0-9a-f]{64}")
            val digestsValid = digest.matches(record.distributionSha256) && digest.matches(record.bootstrapSha256)
            return if (identityValid && platformValid && digestsValid) Refinement.Refined(record)
            else Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_ASSEMBLY)
        }

        private fun admitBootstrap(home: Path, expected: String): Refinement<Unit, ManagedHeadlessInstallationFailure> {
            val bootstrap = home.resolve("third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar")
            if (bootstrap.toRealPath() != bootstrap)
                return Refinement.Rejected(ManagedHeadlessInstallationFailure.INVALID_PATH)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(bootstrap).use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    sha.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return if (java.util.HexFormat.of().formatHex(sha.digest()) == expected) Refinement.Refined(Unit)
            else Refinement.Rejected(ManagedHeadlessInstallationFailure.BOOTSTRAP_CHANGED)
        }
    }
}
