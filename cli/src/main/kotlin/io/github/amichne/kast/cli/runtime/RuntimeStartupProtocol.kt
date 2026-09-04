package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Installed IDEA selection requested at the CLI boundary. */
sealed interface StartupIdeHome {
    data object Standard : StartupIdeHome
    data class Explicit(val path: Path) : StartupIdeHome
}

/** Source IDEA system selection requested only by explicit seeding. */
sealed interface StartupIdeaSystem {
    data object Standard : StartupIdeaSystem
    data class Explicit(val path: Path) : StartupIdeaSystem
}

/** Ordinary startup never grants authority to read a user IDEA system directory. */
sealed interface StartupCacheIntent {
    data object Reuse : StartupCacheIntent

    data object Rebuild : StartupCacheIntent

    data class Seed(
        val sourceSystem: StartupIdeaSystem,
        val consentRequest: IndexSeedConsentRequest,
    ) : StartupCacheIntent
}

/** Closed startup request retained from Clikt parsing through runtime admission. */
sealed interface RuntimeStartupRequest {
    val ideHome: StartupIdeHome
    val cacheIntent: StartupCacheIntent

    data object Default : RuntimeStartupRequest {
        override val ideHome: StartupIdeHome = StartupIdeHome.Standard
        override val cacheIntent: StartupCacheIntent = StartupCacheIntent.Reuse
    }

    data class Requested(
        override val ideHome: StartupIdeHome,
        override val cacheIntent: StartupCacheIntent,
    ) : RuntimeStartupRequest
}

enum class SidecarLaunchContextFailure {
    PATH_INVALID,
    PATHS_OVERLAP,
    USER_IDEA_HOME_TARGETED,
}

/**
 * Exact installed runtime plus physically distinct private IntelliJ state directories.
 *
 * Raw paths may leave only at indexer process launch. In particular, the installed IDEA home can
 * never be selected as a writable sidecar state or private-plugin path.
 */
class SidecarLaunchContext private constructor(
    val runtime: InstalledIdeRuntime,
    val importEnvironment: GradleImportEnvironment,
    val cacheRoot: Path,
    val systemDirectory: Path,
    val configDirectory: Path,
    val logDirectory: Path,
    val privatePluginsDirectory: Path,
) {
    companion object {
        fun admit(
            runtime: InstalledIdeRuntime,
            cacheRoot: Path,
            systemDirectory: Path,
            configDirectory: Path,
            logDirectory: Path,
            privatePluginsDirectory: Path,
            importEnvironment: GradleImportEnvironment = GradleImportEnvironment.Empty,
        ): SidecarLaunchContextAdmission {
            val root = canonicalDirectory(cacheRoot)
                ?: return SidecarLaunchContextAdmission.Rejected(
                    SidecarLaunchContextFailure.PATH_INVALID,
                )
            val paths = listOf(
                systemDirectory,
                configDirectory,
                logDirectory,
                privatePluginsDirectory,
            ).map { path ->
                canonicalDirectory(path)
                    ?: return SidecarLaunchContextAdmission.Rejected(
                        SidecarLaunchContextFailure.PATH_INVALID,
                    )
            }
            if (paths.distinct().size != paths.size || pathsOverlap(paths)) {
                return SidecarLaunchContextAdmission.Rejected(
                    SidecarLaunchContextFailure.PATHS_OVERLAP,
                )
            }
            if (paths.take(3).any { it.parent != root }) {
                return SidecarLaunchContextAdmission.Rejected(
                    SidecarLaunchContextFailure.PATHS_OVERLAP,
                )
            }
            if (paths.any { it.startsWith(runtime.home) || runtime.home.startsWith(it) }) {
                return SidecarLaunchContextAdmission.Rejected(
                    SidecarLaunchContextFailure.USER_IDEA_HOME_TARGETED,
                )
            }
            return SidecarLaunchContextAdmission.Admitted(
                SidecarLaunchContext(
                    runtime,
                    importEnvironment,
                    root,
                    paths[0],
                    paths[1],
                    paths[2],
                    paths[3],
                ),
            )
        }

        private fun pathsOverlap(paths: List<Path>): Boolean = paths.indices.any { left ->
            paths.indices.any { right ->
                left != right && paths[left].startsWith(paths[right])
            }
        }

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
    }
}

sealed interface SidecarLaunchContextAdmission {
    data class Admitted(val context: SidecarLaunchContext) : SidecarLaunchContextAdmission
    data class Rejected(val failure: SidecarLaunchContextFailure) : SidecarLaunchContextAdmission
}
