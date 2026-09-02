import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import support.architecture.ArchitectureObservationParser
import support.architecture.ModuleRoleConvention
import support.architecture.gradle.GenerateKastModuleKnowledgeTask
import support.architecture.gradle.VerifyKastArchitectureTask

plugins {
    base
}

val verifyKastArchitecture = tasks.register<VerifyKastArchitectureTask>("verifyKastArchitecture") {
    group = "verification"
    description = "Verifies module dependencies, exports, roles, and permitted effect owners."
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/kast-architecture/verifyKastArchitecture.json"))
}

val trackedAgentGuidePaths = providers.exec {
    workingDir(layout.projectDirectory)
    commandLine("git", "ls-files", "-z", "--cached")
}.standardOutput.asText.map { output ->
    output.split('\u0000')
        .filter { path -> path.substringAfterLast('/') == "AGENTS.md" }
        .sorted()
}
tasks.register<GenerateKastModuleKnowledgeTask>("generateKastModuleKnowledge") {
    group = "distribution"
    description = "Serializes the verified module architecture and scoped AGENTS.md knowledge."
    productVersion.set(providers.provider { project.version.toString() })
    sourceRevision.set(providers.gradleProperty("kastSourceRevision"))
    observedProjectPaths.set(
        verifyKastArchitecture.flatMap { it.observedProjectPaths },
    )
    observedProjectDependencies.set(
        verifyKastArchitecture.flatMap { it.observedProjectDependencies },
    )
    observedExportedProjectDependencies.set(
        verifyKastArchitecture.flatMap { it.observedExportedProjectDependencies },
    )
    observedModuleRoleConventions.set(
        verifyKastArchitecture.flatMap { it.observedModuleRoleConventions },
    )
    classDirectoryOwners.set(
        verifyKastArchitecture.flatMap { it.classDirectoryOwners },
    )
    compiledClassDirectories.from(
        verifyKastArchitecture.map { it.compiledClassDirectories },
    )
    architectureVerificationReport.set(verifyKastArchitecture.flatMap { it.reportFile })
    agentGuidePaths.set(trackedAgentGuidePaths)
    agentGuideFiles.from(trackedAgentGuidePaths)
    rootDirectory.set(layout.projectDirectory)
    outputFile.set(layout.buildDirectory.file("reports/kast-architecture/kast-module-knowledge.json"))
    dependsOn(verifyKastArchitecture)
}

subprojects {
    val modulePath = path
    pluginManager.withPlugin("java") {
        val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
        verifyKastArchitecture.configure {
            observedProjectPaths.add(modulePath)
            compiledClassDirectories.from(mainSourceSet.map { it.output.classesDirs })
            classDirectoryOwners.addAll(
                mainSourceSet.map { sourceSet ->
                    sourceSet.output.classesDirs.files.map { directory ->
                        val relative = rootProject.projectDir.toPath().relativize(
                            directory.toPath(),
                        )
                        "$modulePath${VerifyKastArchitectureTask.CLASS_DIRECTORY_SEPARATOR}" +
                            relative.joinToString("/")
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
        val exportedDependencies = configurations
            .filter { configuration -> configuration.name == "api" }
            .flatMap { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                    "$path${ArchitectureObservationParser.EDGE_SEPARATOR}${dependency.path}"
                }
            }
            .distinct()
            .sorted()
        val roleConventions = ModuleRoleConvention.entries
            .filter { convention -> pluginManager.hasPlugin(convention.pluginId) }
            .map { convention ->
                "$path${ArchitectureObservationParser.ROLE_SEPARATOR}${convention.pluginId}"
            }
        verifyKastArchitecture.configure {
            observedProjectDependencies.addAll(dependencies)
            observedExportedProjectDependencies.addAll(exportedDependencies)
            observedModuleRoleConventions.addAll(roleConventions)
        }
    }
}

private fun String.isProductionDependencyConfiguration(): Boolean =
    !contains("test", ignoreCase = true) &&
    !contains("fixture", ignoreCase = true)
