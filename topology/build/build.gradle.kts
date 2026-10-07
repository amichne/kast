plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.topology"

base {
    archivesName.set("topology-build")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":relation:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
}
