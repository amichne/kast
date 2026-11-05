import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIntellijTarget

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.topology"

base {
    archivesName.set("topology-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellij = selectedIntellijTarget(project).also { it.configureModule(project) }
private val ideaPlatformBuild = intellij.profile.ideBuild
private val sdkLibraries =
    intellij.libraries(
        project,
        IntellijSdkLibraryFamily.PLATFORM,
        IntellijSdkLibraryFamily.KOTLIN,
        IntellijSdkLibraryFamily.JAVA,
    )

// Preserve the existing task entry point without another mutable SDK extraction.
val extractTopologyKotlinPlugin =
    tasks.register("extractTopologyKotlinPlugin") {
        dependsOn(sdkLibraries)
    }

tasks.named<Test>("test") {
    systemProperty("kast.compilerModelTarget", intellij.profile.intellijReleaseLine)
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    implementation(project(":symbol:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))

    compileOnly(sdkLibraries)
    testImplementation(sdkLibraries)
}
