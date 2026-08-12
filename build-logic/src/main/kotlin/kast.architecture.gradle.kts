import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import support.architecture.ArchitectureObservationParser
import support.architecture.gradle.GenerateKastArchitectureProjectionTask
import support.architecture.gradle.VerifyKastArchitectureTask

plugins {
    base
}

val architectureProjection = layout.projectDirectory.file(
    "gradle/architecture/kast-architecture-policy.json",
)

tasks.register<GenerateKastArchitectureProjectionTask>("generateKastArchitectureProjection") {
    group = "verification"
    description = "Generates the checked-in JSON projection of the canonical typed Kotlin architecture policy."
    projectionFile.set(architectureProjection)
}

val verifyKastArchitecture = tasks.register<VerifyKastArchitectureTask>("verifyKastArchitecture") {
    group = "verification"
    description = "Verifies the platform topology, mutation workflow, compiled effects, and migration baseline."
    projectionFile.set(architectureProjection)
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/kast-architecture/verification.json"))
    observedProjectPaths.set(subprojects.map { it.path }.sorted())
}

subprojects {
    val modulePath = path
    pluginManager.withPlugin("java") {
        val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
        verifyKastArchitecture.configure {
            compiledClassDirectories.from(mainSourceSet.map { it.output.classesDirs })
            classDirectoryOwners.addAll(
                mainSourceSet.map { sourceSet ->
                    sourceSet.output.classesDirs.files.map { directory ->
                        val relative = rootProject.projectDir.toPath().relativize(directory.toPath())
                        "$modulePath${VerifyKastArchitectureTask.CLASS_DIRECTORY_SEPARATOR}${relative.joinToString("/")}"
                    }
                },
            )
            dependsOn(tasks.named("classes"))
        }
    }

    afterEvaluate {
        val dependencies = configurations
            .filter { configuration -> configuration.name.isProductionDependencyConfiguration() }
            .flatMap { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                    "$path${ArchitectureObservationParser.EDGE_SEPARATOR}${dependency.path}"
                }
            }
            .distinct()
            .sorted()
        verifyKastArchitecture.configure {
            observedProjectDependencies.addAll(dependencies)
        }
    }
}

private fun String.isProductionDependencyConfiguration(): Boolean =
    !contains("test", ignoreCase = true) &&
        !contains("fixture", ignoreCase = true)
