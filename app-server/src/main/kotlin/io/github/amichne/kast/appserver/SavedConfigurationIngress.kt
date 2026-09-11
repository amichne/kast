package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocument
import io.github.amichne.kast.distribution.contract.configuration.SavedConfigurationDocumentFailure
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable

enum class SavedConfigurationIngressFailure {
    INVALID_SELECTOR,
    INVALID_INSTALLATION,
    INVALID_WORKSPACE,
    UNAVAILABLE,
    NOT_REGULAR,
    CHANGED_DURING_READ,
}

sealed interface SavedConfigurationIngressRejection {
    class File(val failure: SavedConfigurationIngressFailure) : SavedConfigurationIngressRejection

    class Document(val failure: SavedConfigurationDocumentFailure) : SavedConfigurationIngressRejection

    fun reason(): String =
        when (this) {
            is File -> failure.name
            is Document -> failure.name
        }
}

@Serializable
enum class SavedConfigurationObservation {
    ABSENT,
    LOADED,
}

sealed interface SavedConfigurationIngress {
    class Loaded(
        val sources: ConfigurationSources,
        val observation: SavedConfigurationObservation,
        val workspaceObservation: SavedConfigurationObservation = SavedConfigurationObservation.ABSENT,
    ) : SavedConfigurationIngress

    class Rejected(val rejection: SavedConfigurationIngressRejection) : SavedConfigurationIngress
}

/** Only the launcher's pinned selector chooses a saved source; no live-home fallback exists here. */
object InstalledSavedConfigurationIngress {
    fun load(environment: Map<String, String>): SavedConfigurationIngress {
        val selector =
            environment[ConfigurationParameter.CONFIGURATION_FILE.key]
                ?: return SavedConfigurationIngress.Loaded(
                    ConfigurationSources(environment = environment),
                    SavedConfigurationObservation.ABSENT,
                )
        return read(selector, environment)
    }

    fun read(selector: String, environment: Map<String, String>): SavedConfigurationIngress {
        val path =
            try {
                Path.of(selector)
            } catch (_: InvalidPathException) {
                return rejected(SavedConfigurationIngressFailure.INVALID_SELECTOR)
            }
        if (selector.isBlank() || selector.length > 4096 || !path.isAbsolute || path.normalize() != path) {
            return rejected(SavedConfigurationIngressFailure.INVALID_SELECTOR)
        }
        val bytes =
            try {
                val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!before.isRegularFile || before.isSymbolicLink || path.toRealPath() != path) {
                    return rejected(SavedConfigurationIngressFailure.NOT_REGULAR)
                }
                if (before.size() > SavedConfigurationDocument.MAXIMUM_BYTES) {
                    return SavedConfigurationIngress.Rejected(
                        SavedConfigurationIngressRejection.Document(SavedConfigurationDocumentFailure.TOO_LARGE)
                    )
                }
                val content =
                    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
                        it.readNBytes(SavedConfigurationDocument.MAXIMUM_BYTES + 1)
                    }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (
                    before.fileKey() != after.fileKey() ||
                        before.lastModifiedTime() != after.lastModifiedTime() ||
                        before.size() != after.size()
                ) {
                    return rejected(SavedConfigurationIngressFailure.CHANGED_DURING_READ)
                }
                content
            } catch (_: IOException) {
                return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
            } catch (_: SecurityException) {
                return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
            } catch (_: UnsupportedOperationException) {
                return rejected(SavedConfigurationIngressFailure.UNAVAILABLE)
            }
        return when (val parsed = SavedConfigurationDocument.parse(bytes)) {
            is Refinement.Refined ->
                SavedConfigurationIngress.Loaded(
                    parsed.value.configurationSources(environment),
                    SavedConfigurationObservation.LOADED,
                )
            is Refinement.Rejected ->
                SavedConfigurationIngress.Rejected(SavedConfigurationIngressRejection.Document(parsed.failure))
        }
    }

    private fun rejected(failure: SavedConfigurationIngressFailure) =
        SavedConfigurationIngress.Rejected(SavedConfigurationIngressRejection.File(failure))
}
