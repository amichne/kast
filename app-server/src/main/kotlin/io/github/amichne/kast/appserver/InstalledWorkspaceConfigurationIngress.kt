package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationRejection
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

sealed interface WorkspaceConfigurationRejection {
    class Source(val rejection: SavedConfigurationIngressRejection) : WorkspaceConfigurationRejection

    class Configuration(val rejection: ConfigurationRejection) : WorkspaceConfigurationRejection
}

/** Explicit physical roots select one bounded overlay; inspection never registers or creates a workspace. */
object InstalledWorkspaceConfigurationIngress {
    fun resolve(
        installationRoot: Path,
        workspaceRoot: Path,
        environment: Map<String, String>,
    ): Refinement<ResolvedKastConfiguration, WorkspaceConfigurationRejection> =
        when (val loaded = load(installationRoot, workspaceRoot, environment)) {
            is SavedConfigurationIngress.Rejected ->
                Refinement.Rejected(WorkspaceConfigurationRejection.Source(loaded.rejection))
            is SavedConfigurationIngress.Loaded ->
                when (val resolved = ResolvedKastConfiguration.resolve(loaded.sources)) {
                    is Refinement.Refined -> resolved
                    is Refinement.Rejected ->
                        Refinement.Rejected(WorkspaceConfigurationRejection.Configuration(resolved.failure))
                }
        }

    fun load(installationRoot: Path, workspaceRoot: Path, environment: Map<String, String>): SavedConfigurationIngress {
        if (!physicalDirectory(installationRoot)) return rejected(SavedConfigurationIngressFailure.INVALID_INSTALLATION)
        if (!physicalDirectory(workspaceRoot)) return rejected(SavedConfigurationIngressFailure.INVALID_WORKSPACE)
        val installationFile = installationRoot.resolve("config/environment")
        val explicit = environment[ConfigurationParameter.CONFIGURATION_FILE.key]
        if (explicit != null && explicit != installationFile.toString())
            return rejected(SavedConfigurationIngressFailure.INVALID_SELECTOR)
        val installation =
            when (val read = optional(installationRoot, installationFile, environment)) {
                is SavedConfigurationIngress.Rejected -> return read
                is SavedConfigurationIngress.Loaded -> read
            }
        if (explicit != null && installation.observation == SavedConfigurationObservation.ABSENT)
            return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
        val identity =
            MessageDigest.getInstance("SHA-256")
                .digest(workspaceRoot.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val overlay =
            when (
                val read =
                    optional(
                        installationRoot,
                        installationRoot.resolve("config/workspaces/$identity/environment"),
                        emptyMap(),
                    )
            ) {
                is SavedConfigurationIngress.Rejected -> return read
                is SavedConfigurationIngress.Loaded -> read
            }
        return SavedConfigurationIngress.Loaded(
            installation.sources.copy(savedWorkspace = overlay.sources.savedInstallation),
            installation.observation,
            overlay.observation,
        )
    }

    /** Inspect every existing component without following aliases; only a proven missing component means absent. */
    private fun optional(root: Path, file: Path, environment: Map<String, String>): SavedConfigurationIngress {
        var current = root
        for (component in root.relativize(file)) {
            current = current.resolve(component)
            val attributes =
                try {
                    Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return SavedConfigurationIngress.Loaded(
                        ConfigurationSources(environment = environment),
                        SavedConfigurationObservation.ABSENT,
                    )
                } catch (_: IOException) {
                    return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
                } catch (_: SecurityException) {
                    return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
                }
            if (attributes.isSymbolicLink || (current != file && !attributes.isDirectory))
                return rejected(SavedConfigurationIngressFailure.NOT_REGULAR)
        }
        return InstalledSavedConfigurationIngress.read(file.toString(), environment)
    }

    private fun physicalDirectory(path: Path): Boolean =
        try {
            path.isAbsolute &&
                path.normalize() == path &&
                path.toRealPath() == path &&
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun rejected(failure: SavedConfigurationIngressFailure) =
        SavedConfigurationIngress.Rejected(SavedConfigurationIngressRejection.File(failure))
}
