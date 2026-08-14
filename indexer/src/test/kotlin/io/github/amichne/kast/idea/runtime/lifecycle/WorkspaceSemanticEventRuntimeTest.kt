package io.github.amichne.kast.idea

import com.intellij.compiler.CompilerConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceVfsSignalClassifier
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

@TestApplication
class WorkspaceSemanticEventRuntimeTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture = projectFixture.moduleFixture("main")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runtime observes annotation processor artifact changes as semantic environment events`() {
        val project = projectFixture.get()
        waitUntilIndexesAreReady(project)
        val workspaceRoot = tempDir.resolve("workspace").also { path -> Files.createDirectories(path) }
        val processorArtifact = tempDir.resolve("processors/demo.jar").also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "processor")
        }
        val profile = annotationProcessorProfile(project, processorArtifact)
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(project, workspaceRoot)
        val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity).also { it.ensureSchema() }
        val observed = AtomicReference<WorkspaceSignal?>()
        val indexing = KastIdeaProjectIndexing(
            project = project,
            workspaceIdentity = workspaceIdentity,
            config = KastConfig.defaults(),
            workspaceGenerationPublication = TestWorkspaceGenerationPublication(),
            indexStore = store,
            semanticAdmission = readyAdmission(project),
            observeWorkspaceEvents = { _, scope, _ ->
                observed.set(WorkspaceVfsSignalClassifier(scope).classify(processorArtifact))
                AutoCloseable {}
            },
            refreshWorkspace = { _, _, _ -> },
            runProjectIndexing = { _, _ -> },
            waitForNextPass = { false },
            resolveWorkspaceStateIdentity = { WorkspaceStateIdentity("stable") },
        )

        try {
            indexing.start()
            indexing.awaitTermination()

            assertEquals(WorkspaceSignal.SemanticEnvironment, observed.get())
        } finally {
            indexing.cancel()
            store.close()
            clearProfile(profile)
        }
    }

    private fun annotationProcessorProfile(
        project: Project,
        processorArtifact: Path,
    ): ProcessorConfigProfile {
        lateinit var profile: ProcessorConfigProfile
        updateCompilerConfiguration {
            profile = CompilerConfiguration.getInstance(project)
                .addNewProcessorProfile("kast-runtime-semantic-event-test")
            profile.setEnabled(true)
            profile.setProcessorPath(processorArtifact.toString())
            profile.setObtainProcessorsFromClasspath(false)
            profile.addModuleName(moduleFixture.get().name)
        }
        return profile
    }

    private fun clearProfile(profile: ProcessorConfigProfile) {
        updateCompilerConfiguration {
            profile.clearModuleNames()
            profile.setEnabled(false)
            profile.setProcessorPath("")
        }
    }

    private fun updateCompilerConfiguration(action: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction(action)
        }
    }

    private fun readyAdmission(project: Project): IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(
        project = project,
        inspectProject = { IdeaIndexSemanticAdmission.Inspection.Ready },
    )
}
