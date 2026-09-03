import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.source"

base {
    archivesName.set("source-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val sourceIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/source-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractSourceIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(sourceIdeaDistribution)
    ideaVersion.set(ideaDistributionVersion)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private val kotlinPluginLibs: ConfigurableFileCollection = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            include("**/plugins/Kotlin/lib/**/*.jar")
            exclude("**/plugins/Kotlin/lib/jps/**")
            exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
        }
    },
).builtBy(extractSourceIdeaDistribution)

private val javaPluginLibs: ConfigurableFileCollection = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            include("**/plugins/java/lib/**/*.jar")
        }
    },
).builtBy(extractSourceIdeaDistribution)

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))

    sourceIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation(javaPluginLibs)
}
