package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock

/** Request-local IntelliJ source-file authority; no project-wide classification is cached. */
object IntellijProjectFileIndexClassifier {
    /**
     * Proof transition: `(Project, VirtualFile) -> IntellijProjectFileClassification`.
     *
     * A [IntellijProjectFileClassification.Source] proves that the live IntelliJ file index
     * reported source membership, exact module ownership, exact content/source roots, test state,
     * and generated state in the caller's read action. [ProjectFileClassificationFailure] is the
     * closed expected failure. Raw IntelliJ values are extracted only inside this adapter.
     */
    @RequiresReadLock
    fun classify(
        project: Project,
        file: VirtualFile,
    ): IntellijProjectFileClassification {
        if (project.isDisposed) {
            return IntellijProjectFileClassification.Rejected(
                ProjectFileClassificationFailure.PROJECT_DISPOSED,
            )
        }
        if (!file.isValid) {
            return IntellijProjectFileClassification.Rejected(
                ProjectFileClassificationFailure.FILE_INVALID,
            )
        }
        if (file.isDirectory) {
            return IntellijProjectFileClassification.Rejected(
                ProjectFileClassificationFailure.FILE_IS_DIRECTORY,
            )
        }
        return try {
            val index = ProjectFileIndex.getInstance(project)
            val observation = if (index.isInSourceContent(file)) {
                ProjectFileIndexSourceObservation.Source(
                    fileUrl = file.url,
                    moduleName = index.getModuleForFile(file)?.name,
                    contentRootUrl = index.getContentRootForFile(file)?.url,
                    sourceRootUrl = index.getSourceRootForFile(file)?.url,
                    testSource = index.isInTestSourceContent(file),
                    generatedSource = index.isInGeneratedSources(file),
                )
            } else if (index.isInLibrary(file)) {
                ProjectFileIndexSourceObservation.Library(file.url)
            } else {
                ProjectFileIndexSourceObservation.NotSource(file.url)
            }
            classifyProjectFileIndexObservation(observation)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: RuntimeException) {
            IntellijProjectFileClassification.Rejected(
                ProjectFileClassificationFailure.INDEX_OBSERVATION_FAILED,
            )
        } catch (_: LinkageError) {
            IntellijProjectFileClassification.Rejected(
                ProjectFileClassificationFailure.INDEX_OBSERVATION_FAILED,
            )
        }
    }
}
