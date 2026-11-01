plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.topology"

base {
    archivesName.set("topology-service")
}

dependencies {
    implementation(project(":topology:contract"))
    testImplementation(project(":evidence:topology-sqlite"))
}
