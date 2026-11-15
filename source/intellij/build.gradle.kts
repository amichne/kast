import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.source"

base {
    archivesName.set("source-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

val sourceIdeaDistribution =
    configurations.create("sourceIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val kotlinPluginLibs: FileCollection = ideaLibraries(sourceIdeaDistribution, IdeaLibraryFamily.KOTLIN)

private val javaPluginLibs: FileCollection = ideaLibraries(sourceIdeaDistribution, IdeaLibraryFamily.JAVA)

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    sourceIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaPlatformBuild@zip") {
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
    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:syntax-psi:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation(javaPluginLibs)
    testImplementation(kotlinPluginLibs)
}
