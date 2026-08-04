package io.github.amichne.kast.idea.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceVfsSignalClassifierTest {
    private val root = Path.of("/workspace").toAbsolutePath().normalize()
    private val workspaceConfig = Path.of("/state/workspaces/demo/config.toml").toAbsolutePath().normalize()
    private val globalConfig = Path.of("/state/config/config.toml").toAbsolutePath().normalize()
    private val classifier = WorkspaceVfsSignalClassifier(
        WorkspaceVfsObservationScope(
            workspaceRoot = root,
            configurationFiles = setOf(workspaceConfig, globalConfig),
        ),
    )

    @Test
    fun `classifies source build scope and Git worktree signals`() {
        assertEquals(WorkspaceSignal.Source, classifier.classify(root.resolve("src/main/App.kt")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("build.gradle.kts")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("conventions/custom.gradle")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("gradle/libs.versions.toml")))
        assertEquals(WorkspaceSignal.Scope, classifier.classify(root.resolve(".kastignore")))
        assertEquals(WorkspaceSignal.GitWorktree, classifier.classify(root.resolve(".git/index.lock")))
    }

    @Test
    fun `ignores generated and unrelated paths`() {
        assertNull(classifier.classify(root.resolve("build/classes/App.class")))
        assertNull(classifier.classify(root.resolve("README.md")))
        assertNull(classifier.classify(Path.of("/other/src/App.kt")))
    }

    @Test
    fun `generated compiler source changes wake reconciliation without claiming admission`() {
        assertEquals(
            WorkspaceSignal.Source,
            classifier.classify(root.resolve("build/generated/ksp/main/kotlin/demo/Generated.kt")),
        )
    }

    @Test
    fun `classifies only authoritative external configuration files`() {
        assertEquals(WorkspaceSignal.Configuration, classifier.classify(workspaceConfig))
        assertEquals(WorkspaceSignal.Configuration, classifier.classify(globalConfig))
        assertNull(classifier.classify(Path.of("/other/config.toml")))
        assertNull(classifier.classify(root.resolve("config.toml")))
    }

    @Test
    fun `classifies explicit build compiler source and classpath authorities outside workspace`() {
        val buildRoot = Path.of("/build-root").toAbsolutePath().normalize()
        val nestedWorkspace = buildRoot.resolve("modules/app")
        val compilerSourceRoot = buildRoot.resolve("shared/src/main/java")
        val classpathJar = Path.of("/dependencies/compiler-plugin.jar").toAbsolutePath().normalize()
        val classesRoot = Path.of("/dependencies/classes").toAbsolutePath().normalize()
        val scoped = WorkspaceVfsSignalClassifier(
            WorkspaceVfsObservationScope(
                workspaceRoot = nestedWorkspace,
                buildSemanticRoot = buildRoot,
                configurationFiles = emptySet(),
                compilerSourceRoots = { setOf(compilerSourceRoot) },
                classpathRoots = { setOf(classpathJar, classesRoot) },
            ),
        )

        assertEquals(WorkspaceSignal.BuildSemantic, scoped.classify(buildRoot.resolve("gradle/libs.versions.toml")))
        assertEquals(WorkspaceSignal.BuildSemantic, scoped.classify(buildRoot.resolve("included/buildSrc/src/Plugin.kt")))
        assertEquals(WorkspaceSignal.Source, scoped.classify(compilerSourceRoot.resolve("demo/Shared.java")))
        assertEquals(WorkspaceSignal.SemanticEnvironment, scoped.classify(classpathJar))
        assertEquals(WorkspaceSignal.SemanticEnvironment, scoped.classify(classesRoot.resolve("demo/Shared.class")))
    }
}
