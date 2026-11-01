package support.architecture

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import support.architecture.gradle.CollectModuleBuildFacts

class GradleToolingArchitectureCoverageTest {
    @Test
    fun `late registered Gradle tooling classes retain architecture effect ownership`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val adapter = ProjectBuilder.builder().withParent(root).withName("adapter").build()
        adapter.pluginManager.apply("kast.module-facts")
        val tooling = adapter.extensions.getByType(SourceSetContainer::class.java).create("gradleTooling")

        val collection = adapter.tasks.getByName("collectModuleBuildFacts") as CollectModuleBuildFacts
        val owners = collection.facts.get().classDirectoryOwners
        assertTrue(owners.any { it.startsWith(":adapter|") && it.endsWith("classes/java/gradleTooling") })
        val artifacts = adapter.configurations.getByName("kastProductionClasses").outgoing.artifacts
        assertTrue(artifacts.files.files.containsAll(tooling.output.classesDirs.files))
        assertTrue(artifacts.buildDependencies.getDependencies(null).any {
            it.path == ":adapter:compileGradleToolingJava"
        })
    }
}
