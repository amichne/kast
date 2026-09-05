package support.architecture

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import support.architecture.gradle.VerifyKastArchitectureTask

class GradleToolingArchitectureCoverageTest {
    @Test
    fun `late registered Gradle tooling classes retain architecture effect ownership`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val adapter = ProjectBuilder.builder().withParent(root).withName("adapter").build()
        root.pluginManager.apply("kast.architecture")
        adapter.pluginManager.apply("java")
        val tooling = adapter.extensions.getByType(SourceSetContainer::class.java).create("gradleTooling")

        val verification = root.tasks.getByName("verifyKastArchitecture") as VerifyKastArchitectureTask
        val owners = verification.classDirectoryOwners.get()
        assertTrue(owners.any { it.startsWith(":adapter|") && it.endsWith("classes/java/gradleTooling") })
        assertTrue(verification.compiledClassDirectories.files.containsAll(tooling.output.classesDirs.files))
        assertTrue(verification.taskDependencies.getDependencies(verification).any {
            it.path == ":adapter:gradleToolingClasses"
        })
    }
}
