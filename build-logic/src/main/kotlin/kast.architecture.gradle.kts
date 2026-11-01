import conventions.GenerateKnowledgeDocsTask
import conventions.jsoncontracts.KnowledgeDocsRequest
import org.gradle.api.tasks.Sync
import support.architecture.gradle.GenerateKastModuleKnowledgeTask
import support.architecture.gradle.GenerateVerificationGraphTask
import support.architecture.gradle.TopologyImpactTask
import support.architecture.gradle.VerifyKastArchitectureTask
import support.knowledge.GenerateInstalledKnowledgeTask

plugins {
    base
}

val moduleProjects = subprojects.filter { it.buildFile.isFile }
val modulePaths = moduleProjects.map { it.path }
val moduleBuildFiles = moduleProjects.map { it.buildFile }
fun moduleArtifacts(variant: String): FileCollection = files(modulePaths.map { module ->
    val configurationName = "${module.trim(':').replace(':', '_')}$variant"
    val scope = configurations.dependencyScope("${configurationName}Dependencies") {
        dependencies.add(project.dependencies.project(mapOf("path" to module, "configuration" to variant)))
    }
    configurations.resolvable(configurationName) {
        isTransitive = false
        extendsFrom(scope.get())
    }
})
val moduleFacts = moduleArtifacts("kastModuleFacts")
val productionClasses = moduleArtifacts("kastProductionClasses")
val productionSources = moduleArtifacts("kastProductionSources")

val generateVerificationGraph = tasks.register<GenerateVerificationGraphTask>("generateVerificationGraph") {
    group = "verification"
    description = "Records consumers through production, test, fixture, and generated-contract project dependencies."
    moduleReports.from(moduleFacts)
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/ci/verification-graph.json"))
    graphInputs.from(moduleBuildFiles)
    graphInputs.from(buildFile, "settings.gradle.kts", "settings.gradle", "gradle.properties", "mise.toml", "gradle/libs.versions.toml")
    graphInputs.from(fileTree("build-logic") {
        include("*.gradle.kts", "src/main/**")
    })
}

val verifyKastArchitecture = tasks.register<VerifyKastArchitectureTask>("verifyKastArchitecture") {
    group = "verification"
    description = "Verifies module dependencies, exports, roles, and permitted effect owners."
    compiledClassDirectories.from(productionClasses)
    rootDirectory.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/kast-architecture/verifyKastArchitecture.json"))
    moduleReports.from(moduleFacts)
}

tasks.register<TopologyImpactTask>("topologyImpact") {
    group = "help"
    description = "Reports consumers of selected Kast modules through declared production project dependencies."
    val enabled = providers.gradleProperty("kastTopologyEnabled").orElse("true")
    if (enabled.get() == "true") moduleReports.from(moduleFacts)
    selectedModules.set(providers.gradleProperty("kastTopologyModules").map { raw ->
        if (raw.isBlank()) emptyList() else raw.split(',').map(String::trim)
    }.orElse(emptyList()))
    enabledSetting.set(enabled)
    reportFile.set(layout.buildDirectory.file("reports/topology/topology-impact.json"))
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
    sourceFiles.from(files(productionSources).asFileTree.matching { include("**/*.kt") })
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
    productVersion.set(providers.gradleProperty("controlVersion").orElse(providers.provider { project.version.toString() }))
    sourceRevision.set(providers.gradleProperty("kastSourceRevision"))
    moduleReports.from(moduleFacts)
    compiledClassDirectories.from(productionClasses)
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
    productVersion.set(providers.gradleProperty("controlVersion").orElse(providers.provider { project.version.toString() }))
    sourceRevision.set(currentSourceRevision)
    moduleProjectPaths.set(modulePaths)
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
