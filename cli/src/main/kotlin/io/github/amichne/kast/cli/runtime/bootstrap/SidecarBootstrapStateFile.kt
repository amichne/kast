package io.github.amichne.kast.cli.runtime.bootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapDocumentFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal sealed interface SidecarBootstrapStateFileFailure {
    data object PathRejected : SidecarBootstrapStateFileFailure
    data object FilesystemRejected : SidecarBootstrapStateFileFailure
    data class DocumentRejected(
        val failure: SemanticRuntimeBootstrapDocumentFailure,
    ) : SidecarBootstrapStateFileFailure
}

internal sealed interface SidecarBootstrapStateObservation {
    data class Observed(
        val state: SemanticRuntimeBootstrapState,
    ) : SidecarBootstrapStateObservation

    data class Rejected(
        val failure: SidecarBootstrapStateFileFailure,
    ) : SidecarBootstrapStateObservation
}

/** Read-only exact-cache adapter for the child-owned typed bootstrap document. */
internal object SidecarBootstrapStateFile {
    fun observe(path: Path): SidecarBootstrapStateObservation {
        path.admittedParent()
            ?: return SidecarBootstrapStateObservation.Rejected(
                SidecarBootstrapStateFileFailure.PathRejected,
            )
        val document = try {
            Files.readString(path)
        } catch (_: IOException) {
            return SidecarBootstrapStateObservation.Rejected(
                SidecarBootstrapStateFileFailure.FilesystemRejected,
            )
        } catch (_: SecurityException) {
            return SidecarBootstrapStateObservation.Rejected(
                SidecarBootstrapStateFileFailure.FilesystemRejected,
            )
        }
        return when (val decoded = SemanticRuntimeBootstrapCodec.decode(document.trim())) {
            is Refinement.Refined -> SidecarBootstrapStateObservation.Observed(decoded.value)
            is Refinement.Rejected -> SidecarBootstrapStateObservation.Rejected(
                SidecarBootstrapStateFileFailure.DocumentRejected(decoded.failure),
            )
        }
    }

    private fun Path.admittedParent(): Path? {
        if (
            !isAbsolute ||
            normalize() != this ||
            fileName?.toString() != SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME ||
            Files.isSymbolicLink(this)
        ) {
            return null
        }
        val physicalParent = try {
            parent?.toRealPath()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return physicalParent?.takeIf { candidate ->
            candidate == parent && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
        }
    }
}
