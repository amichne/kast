import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-write")
}

group = "${rootProject.group}.change.apply"

base {
    archivesName.set("change-apply-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val applyIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/change-apply-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractApplyIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(applyIdeaDistribution)
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
).builtBy(extractApplyIdeaDistribution)

private val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
}

private val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

private val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

dependencies {
    implementation(project(":change:apply:spi"))
    implementation(project(":change:contract"))

    applyIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}
