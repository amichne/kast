plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.topology"

base {
    archivesName.set("topology-build")
}

dependencies {
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
}
