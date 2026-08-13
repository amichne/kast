import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.symbol"

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:spi"))

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
}
