import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.change.verify"

base {
    archivesName.set("change-verify-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val verifyIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/change-verify-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractVerifyIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(verifyIdeaDistribution)
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
).builtBy(extractVerifyIdeaDistribution)

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
    implementation(project(":change:contract"))
    implementation(project(":change:verify:spi"))
    implementation(project(":workspace:contract"))

    verifyIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}
