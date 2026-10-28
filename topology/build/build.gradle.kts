plugins {
    id("kast.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    id("kast.role.service")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

group = "${providers.gradleProperty("GROUP").get()}.topology"

base {
    archivesName.set("topology-build")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":relation:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
}

tasks.test {
    systemProperty(
        "kast.historical.gitAdapter",
        File(repositoryRoot.asFile, "packaging/topology/git_attribution.py").absolutePath,
    )
}
