import conventions.GenerateKnowledgeDocsTask
import conventions.jsoncontracts.KnowledgeDocsRequest
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import support.architecture.ArchitectureObservationParser
import support.architecture.ModuleRoleConvention
import support.architecture.gradle.GenerateKastModuleKnowledgeTask
import support.architecture.gradle.VerifyKastArchitectureTask
import support.knowledge.GenerateInstalledKnowledgeTask

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

val currentSourceRevision = providers.gradleProperty("kastSourceRevision").orElse(
    providers.exec {
        workingDir(layout.projectDirectory)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.map(String::trim)
)

val generateKastDocumentation = tasks.register<GenerateKnowledgeDocsTask>("generateKastDocumentation") {
    group = "distribution"
    description = "Extracts detached public Kotlin declarations and KDoc with isolated PSI syntax evidence."
    repositoryDirectory.set(layout.projectDirectory)
    outputFile.set(layout.buildDirectory.file("generated/knowledge/kotlin-docs.json"))
}

// Reuse the root build's already-isolated compiler/parser configuration without adding compiler PSI to Gradle's
// own plugin classloader. The root build creates the configuration after this convention is applied, so configureEach
// must observe future configurations as well as existing ones.
configurations.configureEach {
    if (name == "jsonContractParser") {
        val parser = this
        generateKastDocumentation.configure {
            parserClasspath.from(
                files(
                    KnowledgeDocsRequest::class.java.protectionDomain.codeSource.location,
                    parser,
                )
            )
        }
    }
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

val generateInstalledKnowledgeBundle = tasks.register<GenerateInstalledKnowledgeTask>("generateInstalledKnowledgeBundle") {
    group = "distribution"
    description = "Joins verified module guidance with detached public Kotlin declaration documentation."
    productVersion.set(providers.provider { project.version.toString() })
    sourceRevision.set(currentSourceRevision)
    moduleProjectPaths.set(verifyKastArchitecture.flatMap { it.observedProjectPaths })
    agentGuidePaths.set(trackedAgentGuidePaths)
    agentGuideFiles.from(trackedAgentGuidePaths)
    repositoryDirectory.set(layout.projectDirectory)
    documentationFile.set(generateKastDocumentation.flatMap { it.outputFile })
    outputDirectory.set(layout.buildDirectory.dir("generated/installed-knowledge"))
    dependsOn(verifyKastArchitecture, generateKastDocumentation)
}

// The existing control product is the distribution owner. Augment it lazily when the root build registers its Sync
// task rather than creating another installation path.
tasks.withType<Sync>().matching { it.name == "stageKastControlProduct" }.configureEach {
    dependsOn(generateInstalledKnowledgeBundle)
    from(generateInstalledKnowledgeBundle.flatMap { it.outputDirectory }) {
        into("share/kast/knowledge")
    }
}

subprojects {
    val modulePath = path
    pluginManager.withPlugin("java") {
        verifyKastArchitecture.configure {
            observedProjectPaths.add(modulePath)
        }
        // The separate Gradle-daemon payload remains production code owned by its adapter module.
        extensions.getByType<SourceSetContainer>()
            .matching { it.name == "main" || it.name == "gradleTooling" }
            .configureEach {
                val sourceSet = this
                val sourceSetProvider = providers.provider { sourceSet }
                verifyKastArchitecture.configure {
                    compiledClassDirectories.from(sourceSetProvider.map { it.output.classesDirs })
                    classDirectoryOwners.addAll(
                        sourceSetProvider.map { production ->
                            production.output.classesDirs.files.map { directory ->
                                val relative = rootProject.projectDir.toPath().relativize(directory.toPath())
                                "$modulePath${VerifyKastArchitectureTask.CLASS_DIRECTORY_SEPARATOR}" +
                                    relative.joinToString("/")
                            }
                        },
                    )
                    dependsOn(tasks.named(sourceSet.classesTaskName))
                }
                generateKastDocumentation.configure {
                    sourceFiles.from(
                        sourceSetProvider.map { production ->
                            production.allSource.matching { include("**/*.kt") }
                        }
                    )
                }
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
