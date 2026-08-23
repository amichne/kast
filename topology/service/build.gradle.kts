plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.topology"

base {
    archivesName.set("topology-service")
}

dependencies {
    implementation(project(":topology:contract"))
    testImplementation(project(":evidence:sqlite"))
}
