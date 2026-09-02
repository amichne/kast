package io.github.amichne.kast.indexer.bootstrap

import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapAttempt
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapAttemptAdmission
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapDocument
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapRejection
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal enum class IndexerBootstrapStatePublication {
    PUBLISHED,
    REJECTED,
}

internal enum class IndexerBootstrapRejectionPublication {
    PUBLISHED,
    UNAVAILABLE,
    AMBIGUOUS,
    REJECTED,
}

internal enum class IndexerBootstrapStatePublisherFailure {
    PATH_UNAVAILABLE,
    PATH_REJECTED,
    ATTEMPT_UNAVAILABLE,
    ATTEMPT_REJECTED,
}

internal sealed interface IndexerBootstrapStatePublisherAdmission {
    data class Admitted(
        val publisher: AdmittedIndexerBootstrapStatePublisher,
    ) : IndexerBootstrapStatePublisherAdmission

    data class Rejected(
        val failure: IndexerBootstrapStatePublisherFailure,
    ) : IndexerBootstrapStatePublisherAdmission
}

private enum class IndexerBootstrapPublicationPhase { ADMITTED, ACTIVE, TERMINAL }

/** Once-admitted exact-path publisher for one monotonic semantic-runtime bootstrap attempt. */
internal class AdmittedIndexerBootstrapStatePublisher private constructor(
    private val path: Path,
    private val attempt: InstalledSemanticRuntimeBootstrapAttempt,
) {
    private var phase = IndexerBootstrapPublicationPhase.ADMITTED

    fun publishStarting(): IndexerBootstrapStatePublication {
        if (phase != IndexerBootstrapPublicationPhase.ADMITTED) {
            return IndexerBootstrapStatePublication.REJECTED
        }
        val publication = publish(attempt.startingDocument())
        phase = when (publication) {
            IndexerBootstrapStatePublication.PUBLISHED -> IndexerBootstrapPublicationPhase.ACTIVE
            IndexerBootstrapStatePublication.REJECTED -> IndexerBootstrapPublicationPhase.TERMINAL
        }
        return publication
    }

    fun publishReady(): IndexerBootstrapStatePublication = publishTerminal(
        attempt.readyDocument(),
    )

    fun publishRejection(
        failures: Set<InstalledKastRuntimeFailure>,
    ): IndexerBootstrapRejectionPublication {
        if (phase != IndexerBootstrapPublicationPhase.ACTIVE) {
            return IndexerBootstrapRejectionPublication.REJECTED
        }
        phase = IndexerBootstrapPublicationPhase.TERMINAL
        return when (val projection = attempt.rejectionDocument(failures)) {
        is InstalledSemanticRuntimeBootstrapRejection.Projected -> when (
            publish(projection.document)
        ) {
            IndexerBootstrapStatePublication.PUBLISHED ->
                IndexerBootstrapRejectionPublication.PUBLISHED
            IndexerBootstrapStatePublication.REJECTED ->
                IndexerBootstrapRejectionPublication.REJECTED
        }
        InstalledSemanticRuntimeBootstrapRejection.Unavailable ->
            IndexerBootstrapRejectionPublication.UNAVAILABLE
        InstalledSemanticRuntimeBootstrapRejection.Ambiguous ->
            IndexerBootstrapRejectionPublication.AMBIGUOUS
        }
    }

    private fun publishTerminal(
        document: InstalledSemanticRuntimeBootstrapDocument,
    ): IndexerBootstrapStatePublication {
        if (phase != IndexerBootstrapPublicationPhase.ACTIVE) {
            return IndexerBootstrapStatePublication.REJECTED
        }
        phase = IndexerBootstrapPublicationPhase.TERMINAL
        return publish(document)
    }

    private fun publish(
        document: InstalledSemanticRuntimeBootstrapDocument,
    ): IndexerBootstrapStatePublication {
        val staging = try {
            Files.createTempFile(path.parent, ".bootstrap-state-", ".partial")
        } catch (_: IOException) {
            return IndexerBootstrapStatePublication.REJECTED
        } catch (_: SecurityException) {
            return IndexerBootstrapStatePublication.REJECTED
        }
        return try {
            Files.writeString(staging, document.boundaryValue() + "\n")
            Files.move(
                staging,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            IndexerBootstrapStatePublication.PUBLISHED
        } catch (_: AtomicMoveNotSupportedException) {
            IndexerBootstrapStatePublication.REJECTED
        } catch (_: IOException) {
            IndexerBootstrapStatePublication.REJECTED
        } catch (_: SecurityException) {
            IndexerBootstrapStatePublication.REJECTED
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
                // An unpublished state document carries no authority.
            }
        }
    }

    companion object {
        private const val PATH_PROPERTY = "kast.bootstrap.state.path"
        private const val ATTEMPT_PROPERTY = "kast.bootstrap.attempt.id"

        /** Parses the launcher-owned path and attempt properties exactly once. */
        internal fun admit(): IndexerBootstrapStatePublisherAdmission {
            val rawPath = System.getProperty(PATH_PROPERTY)
                ?: return rejected(IndexerBootstrapStatePublisherFailure.PATH_UNAVAILABLE)
            val path = try {
                Path.of(rawPath)
            } catch (_: RuntimeException) {
                return rejected(IndexerBootstrapStatePublisherFailure.PATH_REJECTED)
            }
            if (
                !path.isAbsolute ||
                path.normalize() != path ||
                path.fileName?.toString() != InstalledSemanticRuntimeBootstrapAttempt.FILE_NAME ||
                Files.isSymbolicLink(path)
            ) {
                return rejected(IndexerBootstrapStatePublisherFailure.PATH_REJECTED)
            }
            val parent = try {
                path.parent?.toRealPath()
            } catch (_: IOException) {
                return rejected(IndexerBootstrapStatePublisherFailure.PATH_REJECTED)
            } catch (_: SecurityException) {
                return rejected(IndexerBootstrapStatePublisherFailure.PATH_REJECTED)
            }
            if (
                parent == null ||
                parent != path.parent ||
                !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            ) {
                return rejected(IndexerBootstrapStatePublisherFailure.PATH_REJECTED)
            }
            val rawAttempt = System.getProperty(ATTEMPT_PROPERTY)
                ?: return rejected(IndexerBootstrapStatePublisherFailure.ATTEMPT_UNAVAILABLE)
            val attempt = when (val admission =
                InstalledSemanticRuntimeBootstrapAttempt.admit(rawAttempt)) {
                is InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted -> admission.attempt
                is InstalledSemanticRuntimeBootstrapAttemptAdmission.Rejected -> return rejected(
                    IndexerBootstrapStatePublisherFailure.ATTEMPT_REJECTED,
                )
            }
            return IndexerBootstrapStatePublisherAdmission.Admitted(
                AdmittedIndexerBootstrapStatePublisher(path, attempt),
            )
        }

        private fun rejected(
            failure: IndexerBootstrapStatePublisherFailure,
        ): IndexerBootstrapStatePublisherAdmission.Rejected =
            IndexerBootstrapStatePublisherAdmission.Rejected(failure)
    }
}

/** Stable application-facing admission surface for the single publisher implementation. */
internal object IndexerBootstrapStatePublisher {
    fun admit(): IndexerBootstrapStatePublisherAdmission =
        AdmittedIndexerBootstrapStatePublisher.admit()
}
