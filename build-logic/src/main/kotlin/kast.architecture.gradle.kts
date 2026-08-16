import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import support.architecture.ArchitectureObservationParser
import support.architecture.ModuleRoleConvention
import support.architecture.gradle.GenerateKastArchitectureProjectionTask
import support.architecture.gradle.ArchitectureVerificationMode
import support.architecture.gradle.VerifyKastArchitectureTask
import support.architecture.gradle.VerifyNoLegacyArchitectureTask

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

fun registerArchitectureVerification(
    name: String,
    descriptionText: String,
    mode: ArchitectureVerificationMode,
) = tasks.register<VerifyKastArchitectureTask>(name) {
    group = "verification"
    description = descriptionText
    projectionFile.set(architectureProjection)
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/kast-architecture/$name.json"))
    verificationMode.set(mode)
}

val verifyKastModuleGraph = registerArchitectureVerification(
    "verifyKastModuleGraph",
    "Verifies the clean-slate target module graph against observed project membership and edges.",
    ArchitectureVerificationMode.AUTOMATIC,
)
val verifyForbiddenEffects = registerArchitectureVerification(
    "verifyForbiddenEffects",
    "Verifies compiled references against the sole clean-slate effect owners.",
    ArchitectureVerificationMode.AUTOMATIC,
)
val verifyKastArchitecture = registerArchitectureVerification(
    "verifyKastArchitecture",
    "Verifies the migration graph, compiled effects, and exact migration baseline.",
    ArchitectureVerificationMode.MIGRATION,
)
val verifyNoLegacyArchitecture = tasks.register<VerifyNoLegacyArchitectureTask>(
    "verifyNoLegacyArchitecture",
) {
    group = "verification"
    description = "Rejects every legacy aggregate, backend, compatibility route, and authority."
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(
        layout.buildDirectory.file("reports/kast-architecture/verifyNoLegacyArchitecture.txt"),
    )
    observedLegacyModuleRoots.set(
        providers.provider {
            listOf("analysis-api", "analysis-server", "index-store")
                .filter { root -> layout.projectDirectory.dir(root).asFile.isDirectory }
        },
    )
}
val architectureVerifications = listOf(
    verifyKastModuleGraph,
    verifyForbiddenEffects,
    verifyKastArchitecture,
)

subprojects {
    val modulePath = path
    pluginManager.withPlugin("java") {
        val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
        verifyNoLegacyArchitecture.configure {
            observedProjectPaths.add(modulePath)
            productionSourceFiles.from(
                mainSourceSet.map { sourceSet ->
                    sourceSet.allSource.matching {
                        include("**/*.java", "**/*.kt")
                    }
                },
            )
        }
        architectureVerifications.forEach { verification ->
            verification.configure {
                observedProjectPaths.add(modulePath)
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
        architectureVerifications.forEach { verification ->
            verification.configure {
                observedProjectDependencies.addAll(dependencies)
                observedExportedProjectDependencies.addAll(exportedDependencies)
                observedModuleRoleConventions.addAll(roleConventions)
            }
        }
    }
}

private fun String.isProductionDependencyConfiguration(): Boolean =
    !contains("test", ignoreCase = true) &&
    !contains("fixture", ignoreCase = true)
