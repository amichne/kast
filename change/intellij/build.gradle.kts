import org.gradle.api.artifacts.VersionCatalogsExtension
import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIntellijTarget

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
private val intellij = selectedIntellijTarget(project).also { it.configureBuildDirectory(project) }
private val ideaLibs = intellij.libraries(project, IntellijSdkLibraryFamily.PLATFORM)
private val kotlinPluginLibs = intellij.libraries(project, IntellijSdkLibraryFamily.KOTLIN)
private val javaPluginLibs = intellij.libraries(project, IntellijSdkLibraryFamily.JAVA)

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":change:verify"))
    implementation(project(":evidence:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly(catalog.findLibrary("serialization-json").get())
    testImplementation(catalog.findLibrary("serialization-json").get())
    testImplementation(ideaLibs)
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}
