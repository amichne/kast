package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile

private const val IDEA_BUILD_FILE = "Resources/build.txt"
private const val KOTLIN_PLUGIN_JAR = "plugins/Kotlin/lib/kotlin-plugin.jar"
private const val JBR_RELEASE_FILE = "jbr/Contents/Home/release"
private const val JBR_JAVA_EXECUTABLE = "jbr/Contents/Home/bin/java"
private const val IDEA_MAIN_JAR = "lib/intellij.platform.bootstrap.jar"
private const val APPLICATION_STARTER_JAR = "lib/intellij.platform.ide.core.jar"
private const val IDEA_MAIN_CLASS = "com/intellij/idea/Main.class"
private const val APPLICATION_STARTER_CLASS =
    "com/intellij/openapi/application/ApplicationStarter.class"
private val REQUIRED_GRADLE_PLUGIN_DIRECTORIES = listOf(
    "plugins/gradle-plugin",
    "plugins/gradle-java-plugin",
)
private val KOTLIN_PLUGIN_VERSION = Regex("<version>\\s*([^<\\s]+)\\s*</version>")
private val JBR_RELEASE_ENTRY = Regex("([A-Z_]+)=\"([^\"]+)\"")

/** Raw installation candidates supplied by explicit CLI input or standard macOS discovery. */
sealed interface IdeHomeSelection {
    data class Explicit(val path: Path) : IdeHomeSelection
    data class Standard(val candidates: List<Path>) : IdeHomeSelection

    companion object {
        /** Standard install locations; physical identity is proven later by discovery. */
        fun standard(userHome: Path): Standard = Standard(
            listOf(
                Path.of("/Applications/IntelliJ IDEA.app/Contents"),
                userHome.resolve("Applications/IntelliJ IDEA.app/Contents"),
            ),
        )
    }
}

/** Installed IDEA home carrying exact runtime identity and JBR launch authority. */
class InstalledIdeRuntime internal constructor(
    val home: Path,
    val javaExecutable: Path,
    val identity: IdeRuntimeIdentity,
)

sealed interface InstalledIdeRuntimeDiscoveryResult {
    data class Discovered(
        val runtime: InstalledIdeRuntime,
    ) : InstalledIdeRuntimeDiscoveryResult

    data class Rejected(
        val failure: IndexSeedFailure,
    ) : InstalledIdeRuntimeDiscoveryResult
}

/** Refines installed filesystem observations into exactly one supported local IDEA runtime. */
object InstalledIdeRuntimeDiscovery {
    /**
     * Proof transition: `supported pair + payload digest + IdeHomeSelection ->
     * InstalledIdeRuntimeDiscoveryResult`.
     *
     * Establishes one physical IDEA home with the exact build pair, bundled JBR identity and
     * executable, Gradle integration, and required bootstrap classes. Explicit selection retains
     * its exact rejection. Standard selection admits exactly one matching candidate, rejects
     * multiple matches as [IndexSeedFailure.Ambiguity], and never guesses when none match.
     */
    fun discover(
        support: SupportedIdeRuntimePair,
        kastPayloadDigest: String,
        selection: IdeHomeSelection,
    ): InstalledIdeRuntimeDiscoveryResult = when (selection) {
        is IdeHomeSelection.Explicit -> inspect(
            support,
            kastPayloadDigest,
            selection.path,
        )
        is IdeHomeSelection.Standard -> discoverStandard(
            support,
            kastPayloadDigest,
            selection.candidates,
        )
    }

    private fun discoverStandard(
        support: SupportedIdeRuntimePair,
        kastPayloadDigest: String,
        candidates: List<Path>,
    ): InstalledIdeRuntimeDiscoveryResult {
        val distinct = candidates.distinct()
        val discovered = mutableListOf<InstalledIdeRuntime>()
        val rejections = mutableListOf<IndexSeedFailure>()
        distinct.forEach { candidate ->
            when (val result = inspect(support, kastPayloadDigest, candidate)) {
                is InstalledIdeRuntimeDiscoveryResult.Discovered -> discovered += result.runtime
                is InstalledIdeRuntimeDiscoveryResult.Rejected -> rejections += result.failure
            }
        }
        return when (discovered.size) {
            1 -> InstalledIdeRuntimeDiscoveryResult.Discovered(discovered.single())
            in 2..Int.MAX_VALUE -> InstalledIdeRuntimeDiscoveryResult.Rejected(
                IndexSeedFailure.Ambiguity,
            )
            else -> InstalledIdeRuntimeDiscoveryResult.Rejected(
                rejections.filterIsInstance<IndexSeedFailure.Incompatibility>().firstOrNull()
                    ?: IndexSeedFailure.MissingInstallation,
            )
        }
    }

    private fun inspect(
        support: SupportedIdeRuntimePair,
        kastPayloadDigest: String,
        candidate: Path,
    ): InstalledIdeRuntimeDiscoveryResult {
        val home = canonicalDirectory(candidate)
            ?: return rejectedValidation()
        val ideaBuild = readIdeaBuild(home) ?: return rejectedValidation()
        val kotlinPluginBuild = readKotlinPluginBuild(home) ?: return rejectedValidation()
        val jbr = readJbr(home) ?: return rejectedValidation()
        if (!hasRequiredPlatformLayout(home)) return rejectedValidation()
        val identity = when (
            val admission = IdeRuntimeIdentity.admit(
                support,
                IdeRuntimeIdentityCandidate(
                    ideaBuild,
                    kotlinPluginBuild,
                    jbr.identity,
                    kastPayloadDigest,
                ),
            )
        ) {
            is IdeRuntimeIdentityAdmission.Admitted -> admission.identity
            is IdeRuntimeIdentityAdmission.Rejected -> {
                return InstalledIdeRuntimeDiscoveryResult.Rejected(admission.failure)
            }
        }
        return InstalledIdeRuntimeDiscoveryResult.Discovered(
            InstalledIdeRuntime(home, jbr.javaExecutable, identity),
        )
    }

    private fun readIdeaBuild(home: Path): String? = try {
        Files.readString(home.resolve(IDEA_BUILD_FILE)).trim().substringAfter('-', "")
            .takeIf(String::isNotBlank)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readKotlinPluginBuild(home: Path): String? = try {
        ZipFile(home.resolve(KOTLIN_PLUGIN_JAR).toFile()).use { archive ->
            val descriptor = archive.getEntry("META-INF/plugin.xml") ?: return null
            archive.getInputStream(descriptor).bufferedReader().use { reader ->
                KOTLIN_PLUGIN_VERSION.find(reader.readText())?.groupValues?.get(1)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readJbr(home: Path): JbrObservation? {
        val java = try {
            home.resolve(JBR_JAVA_EXECUTABLE).toRealPath()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(java)) {
            return null
        }
        val release = try {
            Files.readAllLines(home.resolve(JBR_RELEASE_FILE))
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        val values = release.mapNotNull { line ->
            JBR_RELEASE_ENTRY.matchEntire(line)?.destructured?.let { (key, value) -> key to value }
        }.toMap()
        val version = values["JAVA_RUNTIME_VERSION"] ?: return null
        val architecture = values["OS_ARCH"] ?: return null
        return JbrObservation("jbr-$version-$architecture", java)
    }

    private fun hasRequiredPlatformLayout(home: Path): Boolean =
        jarContains(home.resolve(IDEA_MAIN_JAR), IDEA_MAIN_CLASS) &&
            jarContains(home.resolve(APPLICATION_STARTER_JAR), APPLICATION_STARTER_CLASS) &&
            REQUIRED_GRADLE_PLUGIN_DIRECTORIES.all { relative ->
                Files.isDirectory(home.resolve(relative), LinkOption.NOFOLLOW_LINKS)
            } &&
            Files.isDirectory(home.resolve("plugins/Kotlin"), LinkOption.NOFOLLOW_LINKS)

    private fun jarContains(path: Path, entry: String): Boolean = try {
        ZipFile(path.toFile()).use { archive -> archive.getEntry(entry) != null }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private data class JbrObservation(
    val identity: String,
    val javaExecutable: Path,
)

private fun canonicalDirectory(path: Path): Path? {
    if (!path.isAbsolute || path.normalize() != path) return null
    return try {
        path.toRealPath().takeIf { canonical ->
            canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun rejectedValidation(): InstalledIdeRuntimeDiscoveryResult =
    InstalledIdeRuntimeDiscoveryResult.Rejected(IndexSeedFailure.ValidationFailure)
