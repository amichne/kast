package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Process input carries an admitted installation; it grants no workspace readiness. */
class ManagedHeadlessLaunch
private constructor(
    val installation: ManagedHeadlessInstallation,
    val options: String,
    private val executablePath: ManagedHeadlessExecutablePath,
) {
    val arguments: List<String>
        get() = listOf(installation.launch.executable, "kast-headless")

    val environment: Map<String, String>
        get() {
            val runtime = installation
            val state = runtime.state
            val java = runtime.launch.bundledJavaHome
            return mapOf(
                "PATH" to executablePath.value,
                "HOME" to runtime.userHome.toString(),
                "JAVA_HOME" to java.toString(),
                "IDEA_JDK" to java.toString(),
                "IDEA_VM_OPTIONS" to runtime.options.toString(),
                "GRADLE_USER_HOME" to runtime.gradleHome.toString(),
                "XDG_RUNTIME_DIR" to state.resolve("tmp").toString(),
                "TMPDIR" to state.resolve("tmp").toString(),
            ) + linuxSupport(runtime)
        }

    companion object {
        private const val MAXIMUM_OPTIONS_BYTES = 65_536L
        private const val MAXIMUM_PRODUCT_BYTES = 1_048_576

        private fun linuxSupport(runtime: ManagedHeadlessInstallation): Map<String, String> {
            if (runtime.launch.layout != SelectedIdeLayout.LINUX_DIRECTORY) return emptyMap()
            val support = Path.of(runtime.launch.home).resolve("lib/kast-linux-support")
            if (!Files.isDirectory(support)) return emptyMap()
            return mapOf(
                "LD_LIBRARY_PATH" to support.resolve("lib").toString(),
                "FONTCONFIG_FILE" to support.resolve("fonts.conf").toString(),
                "FONTCONFIG_PATH" to support.toString(),
                "XDG_CACHE_HOME" to runtime.userHome.resolve(".cache").toString(),
            )
        }

        fun prepare(
            runtime: ManagedHeadlessInstallation,
            executablePath: String? = null,
        ): Refinement<ManagedHeadlessLaunch, ManagedHeadlessLaunchFailure> {
            val search =
                when (
                    val admitted = ManagedHeadlessExecutablePath.parse(runtime.launch.bundledJavaHome, executablePath)
                ) {
                    is Refinement.Rejected -> return admitted
                    is Refinement.Refined -> admitted.value
                }
            return try {
                val path =
                    vendorOptions(runtime) ?: return Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
                val vendor = Files.readAllLines(path).filterNot { it.startsWith("-Xmx") || it.startsWith("-Xms") }
                val lines = vendor + overrides(runtime)
                if (lines.any { it.contains('\n') || it.contains('\r') })
                    Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
                else
                    Refinement.Refined(ManagedHeadlessLaunch(runtime, lines.joinToString("\n", postfix = "\n"), search))
            } catch (_: java.io.IOException) {
                Refinement.Rejected(ManagedHeadlessLaunchFailure.METADATA_UNAVAILABLE)
            } catch (_: kotlinx.serialization.SerializationException) {
                Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
            }
        }

        private fun vendorOptions(runtime: ManagedHeadlessInstallation): Path? {
            val target = runtime.launch
            val home = Path.of(target.home)
            val bytes =
                Files.newInputStream(home.resolve(target.layout.metadata)).use {
                    it.readNBytes(MAXIMUM_PRODUCT_BYTES + 1)
                }
            if (bytes.size > MAXIMUM_PRODUCT_BYTES) return null
            val product = Json { ignoreUnknownKeys = true }.decodeFromString<Product>(bytes.decodeToString())
            val root = if (target.layout == SelectedIdeLayout.MACOS_BUNDLE) home.resolve("Resources") else home
            val specs =
                product.launch.filter { root.resolve(it.launcherPath).normalize().toString() == target.executable }
            if (specs.size != 1) return null
            val path = root.resolve(specs.single().vmOptionsFilePath).normalize()
            return if (path.startsWith(home) && path.toRealPath() == path && Files.size(path) <= MAXIMUM_OPTIONS_BYTES)
                path
            else null
        }

        private fun overrides(runtime: ManagedHeadlessInstallation): List<String> =
            listOf(
                "-Xmx1536m",
                "-Djava.awt.headless=true",
                "-Didea.trust.headless.disabled=false",
                "-Didea.trust.disabled=false",
                "-Didea.trust.all.projects=false",
                "-Didea.auto.reload.plugins=false",
                "-Duser.home=${runtime.userHome}",
                "-Djava.io.tmpdir=${runtime.state}/tmp",
                "-Didea.config.path=${runtime.config}",
                "-Didea.system.path=${runtime.state}/system",
                "-Didea.log.path=${runtime.state}/log",
                "-Didea.plugins.path=${runtime.plugins}",
            ) + linuxHeadlessOptions(runtime.launch.layout)

        private fun linuxHeadlessOptions(layout: SelectedIdeLayout): List<String> =
            if (layout == SelectedIdeLayout.LINUX_DIRECTORY) {
                // The private headless Host does not observe the desktop theme or use its D-Bus session.
                listOf("-Djbr.dbus.enabled=false")
            } else {
                emptyList()
            }
    }

    @Serializable private data class Product(val launch: List<Launch>)

    @Serializable private data class Launch(val launcherPath: String, val vmOptionsFilePath: String)
}

enum class ManagedHeadlessLaunchFailure {
    METADATA_UNAVAILABLE,
    INVALID_METADATA,
    INVALID_EXECUTABLE_PATH,
    WRITE_FAILED,
}

/** The filesystem owner writes only the selected private launch input. */
object ManagedHeadlessLaunchFiles {
    fun write(specification: ManagedHeadlessLaunch): Refinement<Unit, ManagedHeadlessLaunchFailure> =
        try {
            Files.writeString(
                specification.installation.options,
                specification.options,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            Refinement.Refined(Unit)
        } catch (_: java.io.IOException) {
            Refinement.Rejected(ManagedHeadlessLaunchFailure.WRITE_FAILED)
        }
}
