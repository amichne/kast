package io.github.amichne.kast.idea.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceVfsSignalClassifierTest {
    private val root = Path.of("/workspace").toAbsolutePath().normalize()
    private val classifier = WorkspaceVfsSignalClassifier(root)

    @Test
    fun `classifies source build scope and Git worktree signals`() {
        assertEquals(WorkspaceSignal.Source, classifier.classify(root.resolve("src/main/App.kt")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("build.gradle.kts")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("gradle/libs.versions.toml")))
        assertEquals(WorkspaceSignal.Scope, classifier.classify(root.resolve(".kastignore")))
        assertEquals(WorkspaceSignal.GitWorktree, classifier.classify(root.resolve(".git/index.lock")))
    }

    @Test
    fun `ignores generated and unrelated paths`() {
        assertNull(classifier.classify(root.resolve("build/classes/App.class")))
        assertNull(classifier.classify(Path.of("/other/src/App.kt")))
    }
}
