import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.diagnostic"

base {
    archivesName.set("diagnostic-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

val diagnosticIdeaDistribution =
    configurations.create("diagnosticIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val kotlinPluginLibs: FileCollection =
    ideaLibraries(diagnosticIdeaDistribution, IdeaLibraryFamily.KOTLIN) +
        ideaLibraries(diagnosticIdeaDistribution, IdeaLibraryFamily.JAVA)

dependencies {
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(project(":protocol:contract"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    diagnosticIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaPlatformBuild@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
}
