import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("kast.kotlin-library")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-intellij-read")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideHostBuild = catalog.findVersion("ide-host-build").get().requiredVersion

val workspaceReadIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve(
        "kast/workspace-intellij-read-idea-distributions/$ideHostBuild",
    )))
}

val extractWorkspaceReadIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(workspaceReadIdeaDistribution)
    ideaVersion.set(ideHostBuild)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) { configure() }
    },
).builtBy(extractWorkspaceReadIdeaDistribution)

private val ideaLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/lib/intellij.libraries.kotlinx.serialization.*.jar")
}

private val kotlinPluginLibraries: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/kotlin-plugin.jar")
    include("**/plugins/Kotlin/lib/kotlin-plugin-shared.jar")
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":workspace:contract"))

    workspaceReadIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideHostBuild@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibraries)
    compileOnly(kotlinPluginLibraries)
    testImplementation(ideaLibraries)
    testImplementation(kotlinPluginLibraries)
}

val projectAdmissionReport = layout.buildDirectory.file(
    "reports/KVP-014-project-admission.json",
)

val generateExistingProjectAdmissionReport =
    tasks.register<support.delivery.GenerateKvp014ProjectAdmissionReportTask>(
        "generateExistingProjectAdmissionReport",
    ) {
        group = "verification"
        description = "Generates the canonical KVP-014 existing-Project admission report."
        reportFile.set(projectAdmissionReport)
    }

tasks.withType<Test>().configureEach {
    dependsOn(generateExistingProjectAdmissionReport)
    inputs.file(generateExistingProjectAdmissionReport.flatMap(
        support.delivery.GenerateKvp014ProjectAdmissionReportTask::reportFile,
    )).withPathSensitivity(PathSensitivity.NONE)
    systemProperty(
        "kast.existing.project.admission.report",
        generateExistingProjectAdmissionReport.flatMap(
            support.delivery.GenerateKvp014ProjectAdmissionReportTask::reportFile,
        ).get().asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(generateExistingProjectAdmissionReport)
}
