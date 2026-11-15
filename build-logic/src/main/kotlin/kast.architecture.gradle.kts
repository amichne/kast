import support.architecture.gradle.GenerateKastModuleKnowledgeTask
import support.architecture.gradle.GenerateVerificationGraphTask
import support.architecture.gradle.TopologyImpactTask
import support.architecture.gradle.VerifyKastArchitectureTask

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
