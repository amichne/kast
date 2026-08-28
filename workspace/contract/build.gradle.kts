import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-contract")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val kotlinVersion = catalog.findVersion("kotlin").get().requiredVersion

dependencies {
    api(project(":kernel"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
}
