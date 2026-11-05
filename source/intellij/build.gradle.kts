import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIntellijTarget

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.source"

base {
    archivesName.set("source-intellij")
}

private val intellij = selectedIntellijTarget(project).also { it.configureModule(project) }
private val sdkLibraries =
    intellij.libraries(
        project,
        IntellijSdkLibraryFamily.PLATFORM,
        IntellijSdkLibraryFamily.KOTLIN,
        IntellijSdkLibraryFamily.JAVA,
    )

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly(sdkLibraries)
    testImplementation(sdkLibraries)
    testImplementation(testFixtures(project(":workspace:intellij-read")))
}
