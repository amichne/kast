package io.github.amichne.kast.indexer.bootstrap

import io.github.amichne.kast.runtime.composition.InstalledGradleJvmSelectionReport
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeGradleJvmRefinement
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapTerminalFailure
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

/** Detailed log projection receives only the bounded, typed bootstrap document. */
internal fun interface IndexerBootstrapDocumentSink {
    fun published(document: InstalledSemanticRuntimeBootstrapDocument)
}

private val bootstrapDocumentLog = IndexerBootstrapDocumentSink { document ->
    System.err.println("kast-indexer: bootstrap-document: ${document.boundaryValue()}")
}

private enum class IndexerBootstrapPublicationPhase { ADMITTED, ACTIVE, TERMINAL }

/** Once-admitted exact-path publisher for one monotonic semantic-runtime bootstrap attempt. */
internal class AdmittedIndexerBootstrapStatePublisher private constructor(
    private val path: Path,
    private var attempt: InstalledSemanticRuntimeBootstrapAttempt,
    private val documentSink: IndexerBootstrapDocumentSink,
) {
    private var phase = IndexerBootstrapPublicationPhase.ADMITTED
    private var bootstrapPhase = InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME

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

    /** Advances only the next canonical phase; repeated observations are idempotent. */
    fun publishProgress(next: InstalledRuntimeBootstrapPhase): IndexerBootstrapStatePublication {
        if (phase != IndexerBootstrapPublicationPhase.ACTIVE ||
            next.ordinal !in bootstrapPhase.ordinal..bootstrapPhase.ordinal + 1
        ) return IndexerBootstrapStatePublication.REJECTED
        if (next == bootstrapPhase) return IndexerBootstrapStatePublication.PUBLISHED
        val publication = publish(attempt.startingDocument(next))
        when (publication) {
            IndexerBootstrapStatePublication.PUBLISHED -> bootstrapPhase = next
            IndexerBootstrapStatePublication.REJECTED -> phase = IndexerBootstrapPublicationPhase.TERMINAL
        }
        return publication
    }

    /** JVM observations only refine the selecting phase and preserve their exact attempt. */
    fun observeGradleJvm(report: InstalledGradleJvmSelectionReport): IndexerBootstrapStatePublication {
        if (phase != IndexerBootstrapPublicationPhase.ACTIVE ||
            bootstrapPhase != InstalledRuntimeBootstrapPhase.GRADLE_JVM_SELECTION
        ) return IndexerBootstrapStatePublication.REJECTED
        val refined = when (val refinement = attempt.withGradleJvm(report)) {
            is InstalledSemanticRuntimeGradleJvmRefinement.Refined -> refinement.attempt
            InstalledSemanticRuntimeGradleJvmRefinement.ConflictingEvidence ->
                return IndexerBootstrapStatePublication.REJECTED
        }
        val publication = publish(refined.startingDocument(bootstrapPhase))
        when (publication) {
            IndexerBootstrapStatePublication.PUBLISHED -> attempt = refined
            IndexerBootstrapStatePublication.REJECTED -> phase = IndexerBootstrapPublicationPhase.TERMINAL
        }
        return publication
    }

    fun publishTerminalFailure(
        failure: InstalledSemanticRuntimeBootstrapTerminalFailure,
    ): IndexerBootstrapStatePublication = publishTerminal(
        attempt.terminalFailureDocument(bootstrapPhase, failure),
    )

    fun publishReady(): IndexerBootstrapStatePublication =
        if (bootstrapPhase == InstalledRuntimeBootstrapPhase.TRANSPORT_ACTIVATION) {
            publishTerminal(attempt.readyDocument())
        } else {
            IndexerBootstrapStatePublication.REJECTED
        }

    fun publishRejection(
        failures: Set<InstalledKastRuntimeFailure>,
    ): IndexerBootstrapRejectionPublication {
        if (phase != IndexerBootstrapPublicationPhase.ACTIVE) {
            return IndexerBootstrapRejectionPublication.REJECTED
        }
        phase = IndexerBootstrapPublicationPhase.TERMINAL
        return when (val projection = attempt.rejectionDocument(failures, bootstrapPhase)) {
        is InstalledSemanticRuntimeBootstrapRejection.Projected -> when (
            publish(projection.document)
        ) {
            IndexerBootstrapStatePublication.PUBLISHED ->
                IndexerBootstrapRejectionPublication.PUBLISHED
            IndexerBootstrapStatePublication.REJECTED ->
                IndexerBootstrapRejectionPublication.REJECTED
        }
        InstalledSemanticRuntimeBootstrapRejection.Unavailable,
        InstalledSemanticRuntimeBootstrapRejection.Ambiguous -> when (
            publish(attempt.terminalFailureDocument(bootstrapPhase, InstalledSemanticRuntimeBootstrapTerminalFailure.RUNTIME_ASSEMBLY))
        ) {
            IndexerBootstrapStatePublication.PUBLISHED -> IndexerBootstrapRejectionPublication.PUBLISHED
            IndexerBootstrapStatePublication.REJECTED -> IndexerBootstrapRejectionPublication.REJECTED
        }
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
            documentSink.published(document)
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
        internal fun admit(documentSink: IndexerBootstrapDocumentSink): IndexerBootstrapStatePublisherAdmission {
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
                AdmittedIndexerBootstrapStatePublisher(path, attempt, documentSink),
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
    fun admit(documentSink: IndexerBootstrapDocumentSink = bootstrapDocumentLog): IndexerBootstrapStatePublisherAdmission =
        AdmittedIndexerBootstrapStatePublisher.admit(documentSink)
}
