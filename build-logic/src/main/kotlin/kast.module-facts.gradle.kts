import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import support.architecture.ArchitectureObservationParser
import support.architecture.ModuleRoleConvention
import support.architecture.gradle.CollectModuleBuildFacts
import support.architecture.gradle.ModuleBuildFacts

plugins {
    java
}

val modulePath = path
val repositoryDirectory = isolated.rootProject.projectDirectory.asFile.toPath()
val moduleSourceSets = extensions.getByType<SourceSetContainer>()

// Only a selected consumer needs the dependency inventory. Ordinary tasks do not realize it.
val collectModuleBuildFacts = tasks.register<CollectModuleBuildFacts>("collectModuleBuildFacts") {
    val edges = configurations.associate { configuration ->
        configuration.name to configuration.dependencies.withType(ProjectDependency::class.java)
            .filter { it.path != modulePath }
            .map { "$modulePath${ArchitectureObservationParser.EDGE_SEPARATOR}${it.path}" }
    }
    facts.set(ModuleBuildFacts(
        projectPath = modulePath,
        productionDependencies = edges.filterKeys {
            !it.contains("test", ignoreCase = true) && !it.contains("fixture", ignoreCase = true)
        }.values.flatten().distinct().sorted(),
        verificationDependencies = edges.values.flatten().distinct().sorted(),
        exportedDependencies = edges["api"].orEmpty().distinct().sorted(),
        roleConventions = ModuleRoleConvention.entries.filter { pluginManager.hasPlugin(it.pluginId) }
            .map { "$modulePath${ArchitectureObservationParser.ROLE_SEPARATOR}${it.pluginId}" },
        classDirectoryOwners = moduleSourceSets.filter { it.name == "main" || it.name == "gradleTooling" }
            .flatMap { it.output.classesDirs.files }.map {
                "$modulePath|${repositoryDirectory.relativize(it.toPath()).joinToString("/")}"
            }.sorted(),
        sourceRoots = moduleSourceSets.flatMap { it.allSource.srcDirs }.filter {
            it.toPath().startsWith(repositoryDirectory) &&
                !it.toPath().startsWith(layout.buildDirectory.get().asFile.toPath())
        }.map { "$modulePath|${repositoryDirectory.relativize(it.toPath()).joinToString("/")}" }
            .distinct().sorted(),
    ))
    reportFile.set(layout.buildDirectory.file("reports/module-facts/module.json"))
}

configurations.consumable("kastModuleFacts") {
    outgoing.artifact(collectModuleBuildFacts.flatMap { it.reportFile })
}

val productionClasses = configurations.consumable("kastProductionClasses")
val productionSources = configurations.consumable("kastProductionSources")
moduleSourceSets.matching { it.name == "main" || it.name == "gradleTooling" }.configureEach {
    val sourceSet = this
    productionClasses.configure {
        outgoing.artifacts(provider { sourceSet.output.classesDirs.files.toList() }) {
            builtBy(sourceSet.output.classesDirs)
        }
    }
    productionSources.configure {
        outgoing.artifacts(provider { sourceSet.allSource.sourceDirectories.files.toList() }) {
            builtBy(sourceSet.allSource.sourceDirectories)
        }
    }
}
