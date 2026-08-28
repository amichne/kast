import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
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
    exclude("**/lib/intellij.libraries.ktor.utils.jar")
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
    testImplementation(catalog.findLibrary("serialization-json").get())
}

private val workspaceContractFriendPath =
    project(":workspace:contract").layout.buildDirectory.dir("classes/kotlin/main")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(":workspace:contract:compileKotlin")
    compilerOptions.freeCompilerArgs.add(
        workspaceContractFriendPath.map { directory ->
            "-Xfriend-paths=${directory.asFile.absolutePath}"
        },
    )
}

tasks.withType<Test>().configureEach {
    exclude("**/EpochSignalApiContract.class")
}
