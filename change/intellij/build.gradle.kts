import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.intellij-write")
}

group = "${providers.gradleProperty("GROUP").get()}.change"

base {
    archivesName.set("change-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-platform-build").get().requiredVersion

val changeIdeaDistribution =
    configurations.create("changeIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val ideaLibs: FileCollection = ideaLibraries(changeIdeaDistribution, IdeaLibraryFamily.PLATFORM)

private val kotlinPluginLibs: FileCollection = ideaLibraries(changeIdeaDistribution, IdeaLibraryFamily.KOTLIN)

private val javaPluginLibs: FileCollection = ideaLibraries(changeIdeaDistribution, IdeaLibraryFamily.JAVA)

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":change:verify"))
    implementation(project(":evidence:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    changeIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly(catalog.findLibrary("serialization-json").get())
    testImplementation(catalog.findLibrary("serialization-json").get())
    testImplementation(ideaLibs)
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}
