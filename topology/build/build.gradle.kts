plugins {
    id("kast.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    id("kast.role.service")
}

group = "${rootProject.group}.topology"

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
    systemProperty("kast.historical.gitAdapter", rootProject.file("packaging/topology/git_attribution.py").absolutePath)
}
