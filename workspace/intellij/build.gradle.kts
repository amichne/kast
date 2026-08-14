import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.workspace")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val workspaceIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/workspace-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractWorkspaceIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(workspaceIdeaDistribution)
    ideaVersion.set(ideaDistributionVersion)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            configure()
        }
    },
).builtBy(extractWorkspaceIdeaDistribution)

private val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
}

private val gradlePluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/gradle*/lib/**/*.jar")
}

dependencies {
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:spi"))

    workspaceIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibs)
    compileOnly(gradlePluginLibs)
    testImplementation(ideaLibs)
    testImplementation(gradlePluginLibs)
}
