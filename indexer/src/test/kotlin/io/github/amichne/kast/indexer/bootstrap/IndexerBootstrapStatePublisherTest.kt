package io.github.amichne.kast.indexer.bootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeAssemblyFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeWorkspaceFailure
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IndexerBootstrapStatePublisherTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `admitted path and attempt cannot be retargeted and terminal state cannot resurrect`() {
        val document = temporary.toRealPath().resolve("bootstrap-state")
        val other = temporary.toRealPath().resolve("other").also(Files::createDirectory)
            .resolve("bootstrap-state")
        withProperties(document, ATTEMPT) {
            val publisher = (
                IndexerBootstrapStatePublisher.admit() as
                    IndexerBootstrapStatePublisherAdmission.Admitted
                ).publisher
            System.setProperty(PATH_PROPERTY, other.toString())
            System.setProperty(ATTEMPT_PROPERTY, OTHER_ATTEMPT)

            assertEquals(
                IndexerBootstrapStatePublication.PUBLISHED,
                publisher.publishStarting(),
            )
            assertEquals(
                IndexerBootstrapRejectionPublication.PUBLISHED,
                publisher.publishRejection(setOf(projectJvmFailure())),
            )
            assertEquals(
                IndexerBootstrapStatePublication.REJECTED,
                publisher.publishReady(),
            )
            assertEquals(
                Refinement.Refined(
                    SemanticRuntimeBootstrapState.Rejected(
                        attemptId(ATTEMPT),
                        SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE,
                    ),
                ),
                SemanticRuntimeBootstrapCodec.decode(Files.readString(document).trim()),
            )
            assertFalse(Files.exists(other))
        }
    }

    @Test
    fun `publisher persists each ordered phase and rejects skipped phases and early readiness`() {
        val document = temporary.toRealPath().resolve("bootstrap-state")
        withProperties(document, ATTEMPT) {
            val logged = mutableListOf<String>()
            val publisher = (IndexerBootstrapStatePublisher.admit(
                IndexerBootstrapDocumentSink { logged.add(it.boundaryValue()) },
            ) as IndexerBootstrapStatePublisherAdmission.Admitted).publisher
            assertEquals(IndexerBootstrapStatePublication.PUBLISHED, publisher.publishStarting())
            assertEquals(IndexerBootstrapStatePublication.REJECTED, publisher.publishReady())
            assertEquals(IndexerBootstrapStatePublication.REJECTED, publisher.publishProgress(
                io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase.INDEXING,
            ))
            io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase.entries.forEach { phase ->
                assertEquals(IndexerBootstrapStatePublication.PUBLISHED, publisher.publishProgress(phase))
                val state = (SemanticRuntimeBootstrapCodec.decode(Files.readString(document)) as Refinement.Refined).value
                    as SemanticRuntimeBootstrapState.Starting
                assertEquals(phase.name, state.phase.name)
                assertEquals(attemptId(ATTEMPT), state.attemptId)
            }
            assertEquals(IndexerBootstrapStatePublication.PUBLISHED, publisher.publishTerminalFailure(
                io.github.amichne.kast.runtime.composition.semanticbootstrap.InstalledSemanticRuntimeBootstrapTerminalFailure.TRANSPORT_ACTIVATION,
            ))
            val rejection = (SemanticRuntimeBootstrapCodec.decode(Files.readString(document)) as Refinement.Refined).value
                as SemanticRuntimeBootstrapState.Rejected
            assertEquals(SemanticRuntimeBootstrapFailure.TRANSPORT_ACTIVATION_FAILED, rejection.failure)
            assertEquals(io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase.TRANSPORT_ACTIVATION, rejection.phase)
            assertEquals(IndexerBootstrapStatePublication.REJECTED, publisher.publishReady())
            assertEquals(8, logged.size)
            val loggedStates = logged.map { (SemanticRuntimeBootstrapCodec.decode(it) as Refinement.Refined).value }
            assertEquals(7, loggedStates.filterIsInstance<SemanticRuntimeBootstrapState.Starting>().size)
            assertEquals(rejection, loggedStates.last())
            assertEquals(true, logged.all { it.length < 16 * 1024 })
        }
    }

    @Test
    fun `root path is a finite path rejection`() {
        withProperties(Path.of("/"), ATTEMPT) {
            assertEquals(
                IndexerBootstrapStatePublisherAdmission.Rejected(
                    IndexerBootstrapStatePublisherFailure.PATH_REJECTED,
                ),
                IndexerBootstrapStatePublisher.admit(),
            )
        }
    }

    private fun projectJvmFailure(): InstalledKastRuntimeFailure =
        InstalledKastRuntimeFailure.Assembly(
            InstalledRuntimeAssemblyFailure.WorkspacePublication(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(
                    InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE,
                ),
            ),
        )

    private fun attemptId(raw: String) =
        (io.github.amichne.kast.distribution.contract.bootstrap
            .SemanticRuntimeBootstrapAttemptId.admit(raw) as Refinement.Refined).value

    private fun withProperties(
        path: Path,
        attempt: String,
        block: () -> Unit,
    ) {
        val previousPath = System.getProperty(PATH_PROPERTY)
        val previousAttempt = System.getProperty(ATTEMPT_PROPERTY)
        try {
            System.setProperty(PATH_PROPERTY, path.toString())
            System.setProperty(ATTEMPT_PROPERTY, attempt)
            block()
        } finally {
            restore(PATH_PROPERTY, previousPath)
            restore(ATTEMPT_PROPERTY, previousAttempt)
        }
    }

    private fun restore(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    private companion object {
        const val PATH_PROPERTY = "kast.bootstrap.state.path"
        const val ATTEMPT_PROPERTY = "kast.bootstrap.attempt.id"
        const val ATTEMPT = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_ATTEMPT = "123e4567-e89b-42d3-a456-426614174001"
    }
}
