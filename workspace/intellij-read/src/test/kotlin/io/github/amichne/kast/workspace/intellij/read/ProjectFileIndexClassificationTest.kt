package io.github.amichne.kast.workspace.intellij.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProjectFileIndexClassificationTest {
    @Test
    fun `source classification preserves exact IntelliJ authority facts`() {
        val observation = ProjectFileIndexSourceObservation.Source(
            fileUrl = "file:///workspace/app/src/freeDebug/kotlin/Subject.kt",
            moduleName = "android-app",
            contentRootUrl = "file:///workspace/app",
            sourceRootUrl = "file:///workspace/app/src/freeDebug/kotlin",
            testSource = true,
            generatedSource = true,
        )

        val result = classifyProjectFileIndexObservation(observation)

        val source = assertInstanceOf(IntellijProjectFileClassification.Source::class.java, result)
        assertEquals(IntellijFileFactAuthority.PROJECT_FILE_INDEX, source.authority)
        assertEquals(observation.fileUrl, source.file.value)
        assertEquals("android-app", source.module.value)
        assertEquals(observation.contentRootUrl, source.contentRoot.value)
        assertEquals(observation.sourceRootUrl, source.sourceRoot.value)
        assertEquals(IntellijSourceMembership.TEST, source.membership)
        assertEquals(IntellijGeneratedSourceState.GENERATED, source.generated)
    }

    @Test
    fun `non-source classification does not invent ownership`() {
        val observation = ProjectFileIndexSourceObservation.NotSource(
            fileUrl = "file:///workspace/README.md",
        )

        val result = classifyProjectFileIndexObservation(observation)

        val nonSource = assertInstanceOf(
            IntellijProjectFileClassification.NotSource::class.java,
            result,
        )
        assertEquals(IntellijFileFactAuthority.PROJECT_FILE_INDEX, nonSource.authority)
        assertEquals(observation.fileUrl, nonSource.file.value)
    }

    @Test
    fun `library classification preserves exact IntelliJ authority`() {
        val observation = ProjectFileIndexSourceObservation.Library(
            fileUrl = "jar:///workspace/.gradle/kotlin-stdlib.jar!/kotlin/Unit.class",
        )

        val result = classifyProjectFileIndexObservation(observation)

        val library = assertInstanceOf(
            IntellijProjectFileClassification.Library::class.java,
            result,
        )
        assertEquals(IntellijFileFactAuthority.PROJECT_FILE_INDEX, library.authority)
        assertEquals(observation.fileUrl, library.file.value)
    }

    @Test
    fun `source membership without IntelliJ owner fails closed`() {
        val observation = ProjectFileIndexSourceObservation.Source(
            fileUrl = "file:///workspace/src/Orphan.kt",
            moduleName = null,
            contentRootUrl = "file:///workspace",
            sourceRootUrl = "file:///workspace/src",
            testSource = false,
            generatedSource = false,
        )

        val result = classifyProjectFileIndexObservation(observation)

        val rejected = assertInstanceOf(
            IntellijProjectFileClassification.Rejected::class.java,
            result,
        )
        assertEquals(ProjectFileClassificationFailure.MODULE_OWNER_UNAVAILABLE, rejected.failure)
    }
}
