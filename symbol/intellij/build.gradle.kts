import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.symbol"

base {
    archivesName.set("symbol-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val symbolIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/symbol-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractSymbolIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(symbolIdeaDistribution)
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
).builtBy(extractSymbolIdeaDistribution)

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:spi"))

    symbolIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util-text-matching:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util-text-matching:$ideaPlatformBuild")
}
