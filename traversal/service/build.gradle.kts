plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.traversal"

base {
    archivesName.set("traversal-service")
}

dependencies {
    implementation(project(":relation:contract"))
    implementation(project(":traversal:contract"))
    testImplementation(project(":evidence:sqlite"))
    testImplementation(project(":relation:service"))
}
