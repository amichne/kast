plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.relation"

base {
    archivesName.set("relation-service")
}

dependencies {
    implementation(project(":relation:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
}
