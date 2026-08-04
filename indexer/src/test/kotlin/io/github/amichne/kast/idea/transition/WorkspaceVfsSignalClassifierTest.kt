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
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("local.properties")))
        assertEquals(WorkspaceSignal.BuildSemantic, classifier.classify(root.resolve("gradle.lockfile")))
        assertEquals(
            WorkspaceSignal.BuildSemantic,
            classifier.classify(root.resolve("gradle/dependency-locks/runtime.lockfile")),
        )
        assertEquals(WorkspaceSignal.Scope, classifier.classify(root.resolve(".kastignore")))
        assertEquals(WorkspaceSignal.GitWorktree, classifier.classify(root.resolve(".git/index.lock")))
    }

    @Test
    fun `classifies only compiler-visible IDEA configuration`() {
        assertEquals(
            WorkspaceSignal.SemanticEnvironment,
            classifier.classify(root.resolve(".idea/compiler.xml")),
        )
        assertEquals(
            WorkspaceSignal.SemanticEnvironment,
            classifier.classify(root.resolve(".idea/kotlinc.xml")),
        )
        assertEquals(
            WorkspaceSignal.SemanticEnvironment,
            classifier.classify(root.resolve(".idea/misc.xml")),
        )
        assertNull(classifier.classify(root.resolve(".idea/workspace.xml")))
        assertNull(classifier.classify(root.resolve(".idea/codeStyles/Project.xml")))
    }

    @Test
    fun `classifies only compiler-visible build logic source`() {
        assertEquals(
            WorkspaceSignal.BuildSemantic,
            classifier.classify(root.resolve("buildSrc/src/main/groovy/Plugin.groovy")),
        )
        assertEquals(
            WorkspaceSignal.BuildSemantic,
            classifier.classify(root.resolve("buildSrc/src/main/kotlin/Plugin.kt")),
        )
        assertEquals(
            WorkspaceSignal.BuildSemantic,
            classifier.classify(root.resolve("build-logic/src/main/java/Plugin.java")),
        )
        assertNull(classifier.classify(root.resolve("buildSrc/README.md")))
        assertNull(classifier.classify(root.resolve("build-logic/docs/design.txt")))
    }

    @Test
    fun `ignores generated and unrelated paths`() {
        assertNull(classifier.classify(root.resolve("build/classes/App.class")))
        assertNull(classifier.classify(root.resolve("build-logic/build/kotlin/compileKotlin/cacheable/last-build.bin")))
        assertNull(classifier.classify(root.resolve("build-logic/.gradle/executionHistory/executionHistory.bin")))
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
