plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.relation"

base {
    archivesName.set("relation-service")
}

dependencies {
    implementation(project(":relation:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
}
